package com.wenqu.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.service.SamlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.interfaces.RSAPrivateKey;
import java.security.cert.X509Certificate;

/**
 * SAML 2.0 过滤链（企业级身份治理，Phase 1 批次 3）：
 * <ul>
 *   <li><b>只接管 SAML 协议</b>：SAML 链限死 {@code /saml2/**} 与 {@code /login/saml2/**}，
 *       AuthnRequest 生成与 Response 验签交 Spring Security SAML2（其 pom 默认依赖 OpenSAML 4.3.2，
 *       来自 Shibboleth 仓库——OpenSAML 不发布到 Maven Central）；</li>
 *   <li><b>兜底链全放行</b>：应用鉴权仍是拦截器（{@code SecurityConfig}）+ JWT 自有体系，
 *       兜底链 permitAll + 无 CSRF + 不加安全响应头——语义与引入 Spring Security 之前完全一致，
 *       不让默认安全配置打断既有 API（例如 CSRF 会拦掉全部 POST）；</li>
 *   <li><b>认证源来自数据库</b>：{@code c_ai_identity_provider}(type=saml) 每行一个 Relying Party，
 *       每次登录现查（配置热更新即时生效，登录频度低可接受）；</li>
 *   <li>登录成功不走 Spring Security 的 SecurityContext（本系统认 JWT）：SuccessHandler 直接
 *       把 NameID/属性交给 {@code SamlService} 落本地账号并签发 JWT，以一次性 code 跳回前端。</li>
 * </ul>
 * SP 侧地址派生（须配置 config.spBaseUrl，即部署对外可达根地址）：
 * <pre>ACS      = {spBaseUrl}/login/saml2/sso/{providerId}          （登记到 IdP）
 * EntityID = {spBaseUrl}/saml2/service-provider-metadata/{providerId}（IdP 侧 audience）</pre>
 *
 * @author yuanke
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SamlSecurityConfig {

    private final SamlService samlService;

    /** redirectPath 在 session 里的属性前缀（登录入口暂存 → ACS 成功取出带回前端） */
    private static final String SESSION_REDIRECT_KEY = "saml.redirectPath.";

    // ==================== Relying Party 注册表（来自数据库） ====================

    @Bean
    public RelyingPartyRegistrationRepository samlRegistrations() {
        return new DbRelyingPartyRegistrationRepository();
    }

    /**
     * 每次按 providerId 现查库构建（登录一次会查两三次：生成 AuthnRequest + ACS 校验）。
     * 返回 null 表示该 registrationId 不可用 → Spring Security 按认证失败处理（不 500）。
     */
    class DbRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

        @Override
        public RelyingPartyRegistration findByRegistrationId(String registrationId) {
            IdentityProvider p = samlService.getById(registrationId);
            if (p == null || !"saml".equals(p.getType())
                    || p.getEnabled() == null || p.getEnabled() != 1) {
                return null;
            }
            try {
                return build(p);
            } catch (Exception e) {
                log.warn("[SAML] 认证源 RelyingParty 构建失败 provider={}: {}", registrationId, e.getMessage());
                return null;
            }
        }

        /**
         * 6.5 起该接口不再 extends Iterable（全量枚举已移除）；
         * metadata 端点按 registrationId 单查即可。
         */

        private RelyingPartyRegistration build(IdentityProvider p) {
            String spBase = samlService.spBaseUrl(p);
            String id = p.getId();
            String acs = spBase + "/login/saml2/sso/" + id;
            String spEntityId = spBase + "/saml2/service-provider-metadata/" + id;
            JsonNode c = samlService.config(p);

            // 元数据优先（IdP 的 entityId/SSO 地址/验签证书从 metadataUrl 拉取），否则手填三件套
            String metadataUrl = text(c, "metadataUrl");
            RelyingPartyRegistration.Builder b = metadataUrl != null
                    ? RelyingPartyRegistrations.fromMetadataLocation(metadataUrl)
                    : RelyingPartyRegistration.withRegistrationId(id);
            if (metadataUrl == null) {
                String idpEntity = text(c, "idpEntityId");
                String idpSso = text(c, "idpSsoUrl");
                String idpCert = text(c, "idpCertificate");
                if (idpEntity == null || idpSso == null || idpCert == null) {
                    throw new IllegalArgumentException("未配置 metadataUrl 时必须手填 idpEntityId / idpSsoUrl / idpCertificate");
                }
                X509Certificate cert = SamlService.parseCertificate(idpCert);
                var verification = new Saml2X509Credential(cert,
                        Saml2X509Credential.Saml2X509CredentialType.VERIFICATION);
                b.assertingPartyMetadata(ap -> ap
                        .entityId(idpEntity)
                        .singleSignOnServiceLocation(idpSso)
                        .singleSignOnServiceBinding(Saml2MessageBinding.POST)
                        .wantAuthnRequestsSigned(bool(c, "signAuthnRequest", false))
                        .verificationX509Credentials(creds -> creds.add(verification)));
            } else if (bool(c, "signAuthnRequest", false)) {
                b.assertingPartyMetadata(ap -> ap.wantAuthnRequestsSigned(true));
            }

            // SP 侧（EntityID 与 ACS 从 spBaseUrl 派生）
            b.registrationId(id)
                    .entityId(spEntityId)
                    .assertionConsumerServiceLocation(acs)
                    .assertionConsumerServiceBinding(Saml2MessageBinding.POST);

            // SP 签名/解密密钥对（可选）：配了则 AuthnRequest 签名 + 加密断言可解
            String spCert = text(c, "spCertificate");
            String spKey = text(c, "spPrivateKey");
            if (spCert != null && spKey != null) {
                X509Certificate cert = SamlService.parseCertificate(spCert);
                RSAPrivateKey key = SamlService.parsePrivateKey(spKey);
                b.signingX509Credentials(creds -> creds.add(
                                new Saml2X509Credential(key, cert, Saml2X509Credential.Saml2X509CredentialType.SIGNING)))
                        .decryptionX509Credentials(creds -> creds.add(
                                new Saml2X509Credential(key, cert, Saml2X509Credential.Saml2X509CredentialType.DECRYPTION)));
            } else if (spCert != null || spKey != null) {
                log.warn("[SAML] spCertificate 与 spPrivateKey 必须成对配置，当前只配了一个（忽略 SP 签名/解密）provider={}", id);
            }
            return b.build();
        }
    }

    // ==================== 过滤链 ====================

    /**
     * SAML 链：仅 /saml2/** 与 /login/saml2/**。saml2Login 负责 AuthnRequest 重定向与
     * ACS POST 验签；CSRF 关闭（IdP 回来的 ACS POST 不可能带本系统的 CSRF token）。
     * session 走默认按需创建：AuthnRequest 的 InResponseTo 状态存 HttpSession。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain samlFilterChain(HttpSecurity http,
                                               RelyingPartyRegistrationRepository samlRegistrations,
                                               AuthenticationSuccessHandler samlLoginSuccessHandler) throws Exception {
        http
                .securityMatcher("/saml2/**", "/login/saml2/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .saml2Login(saml -> saml
                        .relyingPartyRegistrationRepository(samlRegistrations)
                        .successHandler(samlLoginSuccessHandler));
        return http.build();
    }

    /**
     * 兜底链：其余全部路径放行——鉴权语义交给既有拦截器体系（SecurityConfig 的
     * UserContextInterceptor + AccessControlInterceptor），Spring Security 在这里只是「在场」。
     * headers 全关：不引入引入前不存在的响应头（X-Frame-Options 等），保持行为零变化。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ==================== 成功处理 ====================

    /**
     * ACS 验签通过后：NameID + 属性 → SamlService 落本地账号 + 签发 JWT + 一次性 code，
     * 302 前端回调页。不往 SecurityContext 写任何东西（本系统身份只认 JWT）。
     */
    @Bean
    public AuthenticationSuccessHandler samlLoginSuccessHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            String target;
            try {
                Saml2Authentication auth = (Saml2Authentication) authentication;
                Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) auth.getPrincipal();
                String registrationId = principal.getRelyingPartyRegistrationId();
                String nameId = principal.getName();
                String redirectPath = null;
                HttpSession session = request.getSession(false);
                if (session != null) {
                    Object v = session.getAttribute(SESSION_REDIRECT_KEY + registrationId);
                    if (v instanceof String s && !s.isBlank()) redirectPath = s;
                    session.removeAttribute(SESSION_REDIRECT_KEY + registrationId);
                }
                com.wenqu.ai.model.IdentityProvider p = samlService.loadUsable(registrationId);
                target = samlService.onSuccess(p, nameId, principal.getAttributes(), redirectPath);
            } catch (Exception e) {
                log.warn("[SAML] 登录成功处理失败: {}", e.getMessage());
                target = samlService.loginErrorRedirect("SAML 登录处理失败：" + e.getMessage());
            }
            response.sendRedirect(target);
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private static boolean bool(JsonNode node, String field, boolean dft) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? dft : v.asBoolean(dft);
    }
}
