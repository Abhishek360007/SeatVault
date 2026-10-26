package com.seatvault.security;

import com.seatvault.config.TrustedProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeaderWhenNoProxiesTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of()));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.5"); // not in trusted range
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void honoursForwardedHeaderFromTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.42");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9, 10.0.0.42");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void fallsBackToRemoteAddrWhenTrustedProxySendsNoHeader() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(List.of("10.0.0.0/8")));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.42");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.42");
    }
}
