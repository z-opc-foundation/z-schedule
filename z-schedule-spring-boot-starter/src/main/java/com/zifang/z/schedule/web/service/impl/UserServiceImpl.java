package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.domain.entity.UserDO;
import com.zifang.z.schedule.web.domain.mapper.UserMapper;
import com.zifang.z.schedule.web.service.UserService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;

/**
 * 用户服务 — 持久化实现
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger logger = LogManager.getLogger(UserServiceImpl.class);

    @Resource
    private UserMapper userMapper;

    @PostConstruct
    public void init() {
        try {
            logger.info("UserServiceImpl (DB-backed) initialized, total users={}", userMapper.selectCount(null));
        } catch (Exception e) {
            logger.warn("UserServiceImpl init skipped (table not migrated yet): {}", e.getMessage());
        }
    }

    @Override
    public List<User> getAll() {
        return DoMapper.toUserDTOList(userMapper.selectList(null));
    }

    @Override
    public User getById(int id) {
        return DoMapper.toDTO(userMapper.selectById(id));
    }

    @Override
    public User getByUsername(String username) {
        UserDO d = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        return DoMapper.toDTO(d);
    }

    @Override
    public ReturnT<String> add(User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return ReturnT.fail("用户名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return ReturnT.fail("密码不能为空");
        }

        // 检查用户名是否已存在
        UserDO exist = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, user.getUsername()));
        if (exist != null) {
            return ReturnT.fail("用户名已存在");
        }

        UserDO d = DoMapper.toDO(user);
        // 密码做 MD5 哈希存储
        d.setPassword(md5(user.getPassword()));
        if (d.getRole() == null || d.getRole().isEmpty()) {
            d.setRole("NORMAL");
        }
        Date now = new Date();
        d.setAddTime(now);
        d.setUpdateTime(now);

        userMapper.insert(d);
        logger.info("User added, userId={}, username={}", d.getId(), user.getUsername());
        return new ReturnT<>(ReturnT.SUCCESS_CODE, "success", String.valueOf(d.getId()));
    }

    @Override
    public ReturnT<String> update(User user) {
        if (user.getId() <= 0) {
            return ReturnT.fail("用户ID不能为空");
        }
        UserDO exist = userMapper.selectById(user.getId());
        if (exist == null) {
            return ReturnT.fail("用户不存在");
        }

        if (user.getUsername() != null) {
            if (user.getUsername().trim().isEmpty()) {
                // 置空后这一行再也查不回来（登录按用户名精确匹配），等于把账号销毁
                return ReturnT.fail("用户名不能为空");
            }
            // 只排除自己：把用户名改成自己当前的值不算重名
            UserDO taken = userMapper.selectOne(new LambdaQueryWrapper<UserDO>()
                    .ne(UserDO::getId, user.getId())
                    .eq(UserDO::getUsername, user.getUsername()));
            if (taken != null) {
                // 不先查就直接撞上 uk_username，唯一键违例会以 500 的形式抛给调用方
                return ReturnT.fail("用户名已存在");
            }
            exist.setUsername(user.getUsername());
        }
        if (user.getRole() != null) exist.setRole(user.getRole());
        if (user.getPermission() != null) exist.setPermission(user.getPermission());
        // 如果传入了密码，做 MD5 哈希后更新
        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            exist.setPassword(md5(user.getPassword()));
        }
        exist.setUpdateTime(new Date());

        userMapper.updateById(exist);
        logger.info("User updated, userId={}", user.getId());
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> delete(int id) {
        UserDO exist = userMapper.selectById(id);
        if (exist == null) {
            return ReturnT.fail("用户不存在");
        }
        userMapper.deleteById(id);
        logger.info("User deleted, userId={}", id);
        return ReturnT.success();
    }

    @Override
    public ReturnT<String> login(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return ReturnT.fail("用户名不能为空");
        }
        if (password == null || password.trim().isEmpty()) {
            return ReturnT.fail("密码不能为空");
        }

        UserDO userDO = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        if (userDO == null) {
            return ReturnT.fail("用户不存在");
        }

        String md5Password = md5(password);
        if (!md5Password.equals(userDO.getPassword())) {
            return ReturnT.fail("密码错误");
        }

        logger.info("User login success, username={}", username);
        return ReturnT.success("登录成功", username);
    }

    // ==================== MD5 工具 ====================

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            // 必须显式指定 UTF-8：默认的 String.getBytes() 按 JVM 的 file.encoding 取字节，
            // 同一个含非 ASCII 字符的密码在 GBK 机器和 UTF-8 机器上散列不同——账号在一边建、另一边登不上。
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
