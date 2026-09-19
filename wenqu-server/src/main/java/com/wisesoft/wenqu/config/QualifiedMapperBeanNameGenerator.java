package com.wisesoft.wenqu.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

/**
 * Mapper 的 bean 名改用<b>全限定类名</b>，而不是默认的「简单类名首字母小写」。
 *
 * <p><b>为什么需要它</b>：本工程同时存在两套 Model/Mapper 宇宙——旧实现（{@code model.*}
 * + {@code repository.*}，落在既有的 {@code c_ai_*} 表）与照搬参考实现的部分（{@code models.*}
 * + {@code repository.port.*}，落在参考实现的表名上）。两者按设计各有一份同名 Mapper
 * （{@code UserMapper} / {@code DepartmentMapper} / {@code KnowledgeBaseMapper}）。
 *
 * <p>{@code @MapperScan} 会递归扫描 {@code com.wisesoft.wenqu.repository}（含 {@code .port} 子包），
 * 而 Spring 的默认 bean 名生成器只看简单类名，于是同一个包里出现两个 {@code userMapper}：
 * 启动即抛
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name 'UserMapper' ... conflicts with existing, non-compatible bean definition of same name and class}。
 * 这是<b>扫描器的命名策略</b>问题，不是代码缺陷——两个类本就该共存（表不同、类型不同）。
 *
 * <p>改用全限定名后天然唯一（{@code com.wisesoft.wenqu.repository.UserMapper} /
 * {@code com.wisesoft.wenqu.repository.port.UserMapper}），且注入仍按类型解析（各处都是
 * {@code @Autowired} 或构造器注入，没有按名字取 bean），不影响既有代码。
 *
 * <p><b>另一类必须清干净的坑</b>：{@code javac} 是增量写盘、不删除源文件已不存在的旧
 * {@code .class}。本工程做过一次包迁移（{@code repository/*Mapper} → {@code repository/port/*Mapper}），
 * 旧包下的 {@code .class} 若残留在 {@code target/classes}，会被扫描器一并扫到并触发同样的冲突。
 * 因此 {@code build.sh} 每次编译前会清空 {@code target/classes}（见该脚本注释）。
 */
public class QualifiedMapperBeanNameGenerator extends AnnotationBeanNameGenerator {

    /** 直接返回全限定类名作为 bean 名，跨包唯一。 */
    @Override
    public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
        String className = definition.getBeanClassName();
        if (className == null || className.isEmpty()) {
            return super.generateBeanName(definition, registry);
        }
        return className;
    }
}
