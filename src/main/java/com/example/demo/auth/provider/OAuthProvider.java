package com.example.demo.auth.provider;

import java.util.Optional;

/** A login provider (Google, GitHub, Discord), looked up by its bean name. */
public interface OAuthProvider {
    /**
     * Exchanges the code from the provider's redirect and returns the user's email, but only if
     * the provider confirmed that it belongs to the user. Accounts are matched by email, so an
     * unconfirmed one would let anyone who types in a stranger's address log in as them.
     */
    Optional<String> verifiedEmail(String code, String codeVerifier);
}
