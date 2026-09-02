package com.sina.banking.security;

import java.util.Collection;
import java.util.List;

import com.sina.banking.models.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.sina.banking.models.User;
import com.sina.banking.models.UserStatus;

// Adapts our User entity to what Spring Security needs, without making the entity itself
// depend on a Spring Security interface. Built by AppUserDetailsService, consumed by
// DaoAuthenticationProvider (login) and JwtAuthenticationFilter (per-request token checks).
public class AppUserPrincipal implements UserDetails{

    private final User user;

    public AppUserPrincipal(User user) {
        this.user = user;
    }

    public Integer getId() {
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }

    @Override
    public @Nullable String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus().equals(UserStatus.ACTIVE);
    }
    
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    public boolean isAdmin() {
        return user.getRole().equals(UserRole.ADMIN);
    }
}
