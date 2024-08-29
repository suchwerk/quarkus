package io.quarkus.oidc.client.registration.runtime;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.jboss.logging.Logger;

import io.quarkus.oidc.client.registration.ClientMetadata;
import io.quarkus.oidc.client.registration.OidcClientRegistrationConfig;
import io.quarkus.oidc.client.registration.RegisteredClient;
import io.quarkus.oidc.common.OidcEndpoint;
import io.quarkus.oidc.common.OidcEndpoint.Type;
import io.quarkus.oidc.common.OidcRequestContextProperties;
import io.quarkus.oidc.common.OidcRequestFilter;
import io.quarkus.oidc.common.runtime.OidcCommonUtils;
import io.quarkus.oidc.common.runtime.OidcConstants;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.groups.UniOnItem;
import io.vertx.core.http.HttpHeaders;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;

public class RegisteredClientImpl implements RegisteredClient {
    private static final Logger LOG = Logger.getLogger(RegisteredClientImpl.class);

    private static final String APPLICATION_JSON = "application/json";
    private static final String AUTHORIZATION_HEADER = String.valueOf(HttpHeaders.AUTHORIZATION);
    //https://datatracker.ietf.org/doc/html/rfc7592.html#section-2.2
    private static final Set<String> PRIVATE_PROPERTIES = Set.of(OidcConstants.CLIENT_METADATA_SECRET_EXPIRES_AT,
            OidcConstants.CLIENT_METADATA_ID_ISSUED_AT);

    private final WebClient client;
    private final OidcClientRegistrationConfig oidcConfig;
    private final String registrationClientUri;
    private final String registrationToken;
    private final ClientMetadata registeredMetadata;
    private final Map<OidcEndpoint.Type, List<OidcRequestFilter>> filters;
    private volatile boolean closed;

    public RegisteredClientImpl(WebClient client, OidcClientRegistrationConfig oidcConfig,
            Map<Type, List<OidcRequestFilter>> oidcRequestFilters,
            ClientMetadata registeredMetadata, String registrationClientUri, String registrationToken) {
        this.client = client;
        this.oidcConfig = oidcConfig;
        this.registrationClientUri = registrationClientUri;
        this.registrationToken = registrationToken;
        this.registeredMetadata = registeredMetadata;
        this.filters = oidcRequestFilters;
    }

    @Override
    public ClientMetadata metadata() {
        checkClosed();
        return new ClientMetadata(registeredMetadata.getMetadataString());
    }

    @Override
    public Uni<RegisteredClient> read() {
        checkClosed();
        checkClientRequestUri();
        HttpRequest<Buffer> request = client.getAbs(registrationClientUri);
        request.putHeader(HttpHeaders.ACCEPT.toString(), APPLICATION_JSON);
        return makeRequest(request, Buffer.buffer())
                .transform(resp -> newRegisteredClient(resp));
    }

    @Override
    public Uni<RegisteredClient> update(ClientMetadata newMetadata) {

        checkClosed();
        checkClientRequestUri();
        if (newMetadata.getClientId() != null && !registeredMetadata.getClientId().equals(newMetadata.getClientId())) {
            throw new OidcClientRegistrationException("Client id can not be modified");
        }
        if (newMetadata.getClientSecret() != null
                && !registeredMetadata.getClientSecret().equals(newMetadata.getClientSecret())) {
            throw new OidcClientRegistrationException("Client secret can not be modified");
        }

        JsonObjectBuilder builder = Json.createObjectBuilder();

        JsonObject newJsonObject = newMetadata.getJsonObject();
        JsonObject currentJsonObject = registeredMetadata.getJsonObject();

        LOG.debugf("Current client metadata: %s", currentJsonObject.toString());

        // Try to ensure the same order of properties as in the original metadata
        for (Map.Entry<String, JsonValue> entry : currentJsonObject.entrySet()) {
            if (PRIVATE_PROPERTIES.contains(entry.getKey())) {
                continue;
            }
            boolean newPropValue = newJsonObject.containsKey(entry.getKey());
            builder.add(entry.getKey(), newPropValue ? newJsonObject.get(entry.getKey()) : entry.getValue());
        }
        for (Map.Entry<String, JsonValue> entry : newJsonObject.entrySet()) {
            if (PRIVATE_PROPERTIES.contains(entry.getKey())) {
                continue;
            }
            if (!currentJsonObject.containsKey(entry.getKey())) {
                builder.add(entry.getKey(), entry.getValue());
            }
        }
        JsonObject json = builder.build();

        LOG.debugf("Updated client metadata: %s", json.toString());

        HttpRequest<Buffer> request = client.putAbs(registrationClientUri);
        request.putHeader(HttpHeaders.CONTENT_TYPE.toString(), APPLICATION_JSON);
        request.putHeader(HttpHeaders.ACCEPT.toString(), APPLICATION_JSON);
        return makeRequest(request, Buffer.buffer(json.toString()))
                .transform(resp -> newRegisteredClient(resp));
    }

    @Override
    public Uni<Void> delete() {
        checkClosed();
        checkClientRequestUri();

        return makeRequest(client.deleteAbs(registrationClientUri), Buffer.buffer())
                .transformToUni(resp -> deleteResponse(resp));
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            try {
                client.close();
            } catch (Exception ex) {
                LOG.debug("Failed to close the client", ex);
            }
            closed = true;
        }
    }

    private UniOnItem<HttpResponse<Buffer>> makeRequest(HttpRequest<Buffer> request, Buffer buffer) {
        if (registrationToken != null) {
            request.putHeader(AUTHORIZATION_HEADER, OidcConstants.BEARER_SCHEME + " " + registrationToken);
        }
        // Retry up to three times with a one-second delay between the retries if the connection is closed
        Uni<HttpResponse<Buffer>> response = filter(request, buffer).sendBuffer(buffer)
                .onFailure(ConnectException.class)
                .retry()
                .atMost(oidcConfig.connectionRetryCount)
                .onFailure().transform(t -> {
                    LOG.warn("OIDC Server is not available:", t.getCause() != null ? t.getCause() : t);
                    // don't wrap it to avoid information leak
                    return new OidcClientRegistrationException("OIDC Server is not available");
                });
        return response.onItem();
    }

    private HttpRequest<Buffer> filter(HttpRequest<Buffer> request, Buffer body) {
        if (!filters.isEmpty()) {
            OidcRequestContextProperties props = new OidcRequestContextProperties();
            for (OidcRequestFilter filter : OidcCommonUtils.getMatchingOidcRequestFilters(filters,
                    OidcEndpoint.Type.CLIENT_CONFIGURATION)) {
                filter.filter(request, body, props);
            }
        }
        return request;
    }

    private RegisteredClient newRegisteredClient(HttpResponse<Buffer> resp) {
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            io.vertx.core.json.JsonObject json = resp.bodyAsJsonObject();
            LOG.debugf("Client metadata has been succesfully updated: %s", json.toString());

            String newRegistrationClientUri = (String) json.remove(OidcConstants.REGISTRATION_CLIENT_URI);
            String newRegistrationToken = (String) json.remove(OidcConstants.REGISTRATION_ACCESS_TOKEN);

            return new RegisteredClientImpl(client, oidcConfig, filters, new ClientMetadata(json.toString()),
                    (newRegistrationClientUri != null ? newRegistrationClientUri : registrationClientUri),
                    (newRegistrationToken != null ? newRegistrationToken : registrationToken));
        } else {
            String errorMessage = resp.bodyAsString();
            LOG.debugf("Client configuration update has failed:  status: %d, error message: %s", resp.statusCode(),
                    errorMessage);
            throw new OidcClientRegistrationException(errorMessage);
        }
    }

    private Uni<Void> deleteResponse(HttpResponse<Buffer> resp) {
        if (resp.statusCode() == 200) {
            LOG.debug("Client has been succesfully deleted");
            return Uni.createFrom().voidItem();
        } else {
            String errorMessage = resp.bodyAsString();
            LOG.debugf("Client delete request has failed:  status: %d, error message: %s", resp.statusCode(),
                    errorMessage);
            return Uni.createFrom().voidItem();
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Registered OIDC Client is closed");
        }
    }

    private void checkClientRequestUri() {
        if (registrationClientUri == null) {
            throw new OidcClientRegistrationException(
                    "Registered OIDC Client can not make requests to the client configuration endpoint");
        }
    }

    @Override
    public String registrationUri() {
        return this.registrationClientUri;
    }

    @Override
    public String registrationToken() {
        return this.registrationToken;
    }

}
