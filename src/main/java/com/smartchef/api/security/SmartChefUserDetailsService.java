package com.smartchef.api.security;

import com.smartchef.api.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permite iniciar sesion indistintamente con username o email. */
@Service
public class SmartChefUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public SmartChefUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        return userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .map(SmartChefUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No existe un usuario con el identificador: " + usernameOrEmail));
    }
}
