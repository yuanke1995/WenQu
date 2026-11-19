package com.wenqu.ai.ssh;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH PTY 终端：浏览器 WebSocket ↔ JSch ChannelShell。
 *
 * <p>协议（JSON 文本帧）：
 * <ul>
 *   <li>客户端 → 服务端：{@code {"type":"input","data":"..."}} / {@code {"type":"resize","cols":120,"rows":30}}</li>
 *   <li>服务端 → 客户端：{@code {"type":"output","data":"..."}} / {@code {"type":"exit"}} / {@code {"type":"error","message":"..."}}</li>
 * </ul>
 *
 * <p>认证：握手 URL 带一次性 ticket（见 {@link SshTerminalTicketService}），不在 URL 里放登录凭据。
 * 终端输入输出不进日志、不落审计（只记一条会话建立/断开）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class SshTerminalWebSocketHandler extends TextWebSocketHandler {

    private final SshTerminalTicketService tickets;
    private final SshHostService hostService;
    private final SshConnectionManager connectionManager;

    private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();

    public SshTerminalWebSocketHandler(SshTerminalTicketService tickets, SshHostService hostService,
                                       SshConnectionManager connectionManager) {
        this.tickets = tickets;
        this.hostService = hostService;
        this.connectionManager = connectionManager;
    }

    private static final class Terminal {
        final SshConnectionManager.ShellHandle shell;
        final Thread pump;
        volatile boolean closed;

        Terminal(SshConnectionManager.ShellHandle shell, Thread pump) {
            this.shell = shell;
            this.pump = pump;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = queryParam(session, "ticket");
        String[] owner = tickets.consume(ticket);
        if (owner == null) {
            send(session, error("终端入场券无效或已过期，请重新打开终端"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String uid = owner[0];
        String hostId = owner[1];
        SshHost host;
        try {
            host = hostService.owned(uid, hostId);
        } catch (IllegalArgumentException e) {
            send(session, error("SSH 主机不存在或不属于你"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        int cols = intParam(session, "cols", 120);
        int rows = intParam(session, "rows", 30);
        SshConnectionManager.ShellHandle shell;
        try {
            shell = connectionManager.openShell(host, hostService.credentialsOf(host), cols, rows);
        } catch (Exception e) {
            send(session, error("SSH 终端连接失败：" + e.getMessage()));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        Thread pump = new Thread(() -> pumpOutput(session, shell), "ssh-term-" + session.getId());
        pump.setDaemon(true);
        terminals.put(session.getId(), new Terminal(shell, pump));
        pump.start();
        log.info("[SSH] 终端建立 uid={} host={} session={}", uid, hostId, session.getId());
    }

    private void pumpOutput(WebSocketSession session, SshConnectionManager.ShellHandle shell) {
        try (InputStream in = shell.input()) {
            byte[] buf = new byte[8192];
            int n;
            while (!Thread.currentThread().isInterrupted() && (n = in.read(buf)) != -1) {
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                send(session, output(text));
            }
        } catch (Exception e) {
            // 连接被关闭属正常路径
        } finally {
            send(session, JSON.toJSONString(Map.of("type", "exit")));
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Terminal t = terminals.get(session.getId());
        if (t == null) return;
        try {
            JSONObject msg = JSON.parseObject(message.getPayload());
            String type = msg.getString("type");
            if ("input".equals(type)) {
                String data = msg.getString("data");
                if (data != null) {
                    OutputStream out = t.shell.output();
                    out.write(data.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } else if ("resize".equals(type)) {
                int cols = msg.getIntValue("cols", 120);
                int rows = msg.getIntValue("rows", 30);
                t.shell.resize(cols, rows);
            }
        } catch (Exception e) {
            send(session, error("终端写入失败：" + e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Terminal t = terminals.remove(session.getId());
        if (t != null) {
            t.closed = true;
            try {
                t.pump.interrupt();
            } catch (Exception ignore) {
            }
            t.shell.close();
            log.info("[SSH] 终端断开 session={}", session.getId());
        }
    }

    private void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ignore) {
        }
    }

    private static String output(String data) {
        return JSON.toJSONString(Map.of("type", "output", "data", data));
    }

    private static String error(String message) {
        return JSON.toJSONString(Map.of("type", "error", "message", message == null ? "" : message));
    }

    private static String queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) return null;
        return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst(name);
    }

    private static int intParam(WebSocketSession session, String name, int def) {
        try {
            String v = queryParam(session, name);
            return v == null ? def : Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }
}
