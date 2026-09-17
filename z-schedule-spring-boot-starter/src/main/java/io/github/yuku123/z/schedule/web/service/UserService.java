package io.github.yuku123.z.schedule.web.service;

import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.core.model.User;

import java.util.List;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 查询所有用户
     *
     * @return 用户列表
     */
    List<User> getAll();

    /**
     * 根据ID查询用户
     *
     * @param id 用户ID
     * @return 用户信息
     */
    User getById(int id);

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息
     */
    User getByUsername(String username);

    /**
     * 新增用户
     *
     * @param user 用户信息
     * @return 操作结果
     */
    ReturnT<String> add(User user);

    /**
     * 更新用户
     *
     * @param user 用户信息
     * @return 操作结果
     */
    ReturnT<String> update(User user);

    /**
     * 删除用户
     *
     * @param id 用户ID
     * @return 操作结果
     */
    ReturnT<String> delete(int id);

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 操作结果（成功返回 token=用户名）
     */
    ReturnT<String> login(String username, String password);
}
