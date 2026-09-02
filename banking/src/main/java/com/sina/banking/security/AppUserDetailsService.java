package com.sina.banking.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.sina.banking.models.User;
import com.sina.banking.repositories.UserRepository;

// The seam DaoAuthenticationProvider calls to go from "a username on a login request" to
// "a user's password hash + authorities". Goes straight to UserRepository rather than
// UserService, since UserService only returns UserResponse DTOs with the hash/role stripped out.
@Component
public class AppUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AppUserDetailsService.class);

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user details for username={}", username);
        // UsernameNotFoundException specifically - DaoAuthenticationProvider expects this type,
        // not our usual NoSuchElementException, and treats it the same as a wrong password
        // (see AuthenticationService) so a login attempt can't be used to enumerate usernames.
        User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));

        return new AppUserPrincipal(user);
    }

}
