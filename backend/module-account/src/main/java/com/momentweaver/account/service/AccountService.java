package com.momentweaver.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.dto.LoginRequest;
import com.momentweaver.account.dto.LoginResponse;
import com.momentweaver.account.dto.RegisterRequest;
import com.momentweaver.account.dto.UserVO;
import com.momentweaver.account.entity.User;
import com.momentweaver.account.entity.Workspace;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.UserMapper;
import com.momentweaver.account.mapper.WorkspaceMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.auth.jwt.JwtTokenProvider;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwt;

    @Transactional
    public LoginResponse register(RegisterRequest req) {
        String id = req.getIdentifier().trim();
        User exists = findByIdentifier(id);
        if (exists != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        User u = new User();
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName() != null && !req.getDisplayName().isBlank()
            ? req.getDisplayName()
            : "用户-" + id.substring(0, Math.min(4, id.length())));
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        if (id.contains("@")) {
            u.setEmail(id);
        } else {
            u.setPhone(id);
        }
        userMapper.insert(u);

        // 注册即建一个默认工作区，并把 owner 加进去
        Workspace ws = new Workspace();
        ws.setOwnerId(u.getId());
        ws.setName(u.getDisplayName() + " 的工作区");
        ws.setCreatedAt(LocalDateTime.now());
        ws.setUpdatedAt(LocalDateTime.now());
        workspaceMapper.insert(ws);

        WorkspaceMember wm = new WorkspaceMember();
        wm.setWorkspaceId(ws.getId());
        wm.setUserId(u.getId());
        wm.setRole("owner");
        wm.setCreatedAt(LocalDateTime.now());
        wm.setUpdatedAt(LocalDateTime.now());
        workspaceMemberMapper.insert(wm);

        return issueToken(u);
    }

    public LoginResponse login(LoginRequest req) {
        User u = findByIdentifier(req.getIdentifier().trim());
        if (u == null || !passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new BusinessException(ResultCode.PASSWORD_INCORRECT);
        }
        if (u.getStatus() == null || u.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        return issueToken(u);
    }

    public UserVO me(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);
        return toVO(u);
    }

    // ---- helpers ----

    private LoginResponse issueToken(User u) {
        String access = jwt.generateAccessToken(u.getId());
        String refresh = jwt.generateRefreshToken(u.getId());
        return new LoginResponse(access, refresh, jwt.getAccessTtlSeconds(), toVO(u));
    }

    private User findByIdentifier(String id) {
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        if (id.contains("@")) {
            q.eq(User::getEmail, id);
        } else {
            q.eq(User::getPhone, id);
        }
        return userMapper.selectOne(q);
    }

    private UserVO toVO(User u) {
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setPhone(u.getPhone());
        vo.setEmail(u.getEmail());
        vo.setDisplayName(u.getDisplayName());
        vo.setAvatarUrl(u.getAvatarUrl());
        return vo;
    }
}
