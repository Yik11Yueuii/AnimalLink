package org.animallink.identity.domain;

public class LoginRateLimitedException extends UnauthorizedException {
    public LoginRateLimitedException() {
        super("用户名或密码错误");
    }
}
