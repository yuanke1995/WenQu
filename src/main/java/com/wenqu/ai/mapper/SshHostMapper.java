package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.ssh.SshHost;
import org.apache.ibatis.annotations.Mapper;

/** SSH 主机（{@code c_ai_ssh_host}）mapper：纯 CRUD。 */
@Mapper
public interface SshHostMapper extends BaseMapper<SshHost> {
}
