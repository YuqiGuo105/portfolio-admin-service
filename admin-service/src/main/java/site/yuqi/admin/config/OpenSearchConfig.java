package site.yuqi.admin.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the OpenSearch REST client against the Aiven endpoint.
 * Credentials are injected via environment variables — never hardcoded.
 *
 * Required env vars:
 *   OPENSEARCH_HOST     — managed OpenSearch hostname
 *   OPENSEARCH_PORT     — managed OpenSearch TLS port
 *   OPENSEARCH_USERNAME — e.g. avnadmin
 *   OPENSEARCH_PASSWORD — (Aiven OpenSearch admin password)
 */
@Configuration
public class OpenSearchConfig {

    @Value("${portfolio.opensearch.host}")
    private String host;

    @Value("${portfolio.opensearch.port}")
    private int port;

    @Value("${portfolio.opensearch.username}")
    private String username;

    @Value("${portfolio.opensearch.password}")
    private String password;

    @Bean
    public RestHighLevelClient openSearchClient() {
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "https"))
                .setHttpClientConfigCallback(httpClientBuilder ->
                        httpClientBuilder.setDefaultCredentialsProvider(creds));

        return new RestHighLevelClient(builder);
    }
}
