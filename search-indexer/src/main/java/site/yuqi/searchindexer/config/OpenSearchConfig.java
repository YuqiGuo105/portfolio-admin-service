package site.yuqi.searchindexer.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchConfig {

    @Value("${portfolio.opensearch.host}")
    private String host;

    @Value("${portfolio.opensearch.port}")
    private int port;

    @Value("${portfolio.opensearch.username:}")
    private String username;

    @Value("${portfolio.opensearch.password:}")
    private String password;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient openSearchClient() {
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        if (!username.isBlank()) {
            creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
        }
        return new RestHighLevelClient(
                RestClient.builder(new HttpHost(host, port, "https"))
                        .setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(creds)));
    }
}
