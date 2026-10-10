package org.animallink.intelligence;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.intelligence.application.CredentialMaterialUnavailable;
import org.animallink.intelligence.infrastructure.HttpCredentialMaterialGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class HttpCredentialMaterialGatewayTest {
    @Test
    void usesTheNarrowIdentityContentContractWithInternalAndTraceHeaders() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Trace-Id")).thenReturn("trace-123");
        server.expect(once(), requestTo("http://identity.test/internal/v1/campus-verifications/v-1/credential-material/content"))
                .andExpect(method(GET))
                .andExpect(header("X-Internal-Service", "intelligence-service"))
                .andExpect(header("X-Trace-Id", "trace-123"))
                .andRespond(withSuccess("image-content".getBytes(), MediaType.IMAGE_PNG));

        var material = new HttpCredentialMaterialGateway(builder.build(), request).load("v-1");

        assertThat(material.contentType()).isEqualTo("image/png");
        assertThat(material.content()).containsExactly("image-content".getBytes());
        server.verify();
    }

    @Test
    void rejectsUnsupportedOrOversizedCredentialContent() {
        RestClient.Builder unsupportedBuilder = RestClient.builder().baseUrl("http://identity.test");
        MockRestServiceServer unsupported = MockRestServiceServer.bindTo(unsupportedBuilder).build();
        unsupported.expect(requestTo("http://identity.test/internal/v1/campus-verifications/v-2/credential-material/content"))
                .andRespond(withSuccess("pdf".getBytes(), MediaType.APPLICATION_PDF));
        assertThatThrownBy(() -> new HttpCredentialMaterialGateway(unsupportedBuilder.build(), mock(HttpServletRequest.class)).load("v-2"))
                .isInstanceOf(CredentialMaterialUnavailable.class)
                .hasMessageContaining("supported image");

        RestClient.Builder largeBuilder = RestClient.builder().baseUrl("http://identity.test");
        MockRestServiceServer large = MockRestServiceServer.bindTo(largeBuilder).build();
        large.expect(requestTo("http://identity.test/internal/v1/campus-verifications/v-3/credential-material/content"))
                .andRespond(withSuccess(new byte[HttpCredentialMaterialGateway.MAX_BYTES + 1], MediaType.IMAGE_JPEG));
        assertThatThrownBy(() -> new HttpCredentialMaterialGateway(largeBuilder.build(), mock(HttpServletRequest.class)).load("v-3"))
                .isInstanceOf(CredentialMaterialUnavailable.class)
                .hasMessageContaining("maximum size");
    }
}
