package com.example.demo;


import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resilience {

  private static Logger logger = LoggerFactory.getLogger(Resilience.class);
  private static PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
  private static RetryConfig retryConfig;
  public static Client getClient() {
    return InstanceHolder.INSTANCE;
  }

  public static void main(String[] args) {

    connManager.setMaxTotal(1);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

     retryConfig = RetryConfig.<Response>custom().maxAttempts(3)
        .retryOnResult(response -> response.getStatus() == 500)
        .retryOnException(ex -> ex instanceof MyCustomException)
        .build();

    CompletableFuture<Response> future = Decorators
        .ofCallable(Resilience::doWork)
        .withThreadPoolBulkhead(ThreadPoolBulkhead.ofDefaults("poolMgr"))
        .withTimeLimiter(TimeLimiter.of(Duration.ofSeconds(5L)), scheduler)
        .withCircuitBreaker(CircuitBreaker.ofDefaults("httpCall"))
        .withRetry(Retry.of("retry", retryConfig), scheduler)
        .get().toCompletableFuture();

    future.handle((response, throwable) -> {
      if (response != null) {
        var responseStr = response.readEntity(String.class);
        logger.info("Total {}, used {}", connManager.getTotalStats().getMax(),
            connManager.getTotalStats().getLeased());
        logger.info("Response Status {} body {}", response.getStatus(), responseStr);
      }
      if (throwable != null) {
        throwable.printStackTrace();
        logger.error("Error Total {}, used {}", connManager.getTotalStats().getMax(),
            connManager.getTotalStats().getLeased());
      }
      return "";
    });
  }

private static Response doWork() {
      WebTarget webTarget = getClient().target("https://httpstat.us/500");
      Invocation.Builder builder = webTarget.request(MediaType.APPLICATION_JSON);
      Response response;
      try {
        response = builder.get();
        if(retryConfig.getResultPredicate().test(response)){
          response.close();
          throw  new MyCustomException();
        }
        return response;
      } catch (Exception ex) {
        logger.error("finish with exception");
        throw ex;
      }
 }

  private static class InstanceHolder {

    private static final Client INSTANCE = createClient();

    private static Client createClient() {
      ClientConfig clientConfig = new ClientConfig();
      clientConfig.property(ClientProperties.READ_TIMEOUT, 2000);
      clientConfig.property(ClientProperties.CONNECT_TIMEOUT, 2000);
      connManager.setMaxTotal(1);
      clientConfig.property(ApacheClientProperties.CONNECTION_MANAGER, connManager);
      clientConfig.connectorProvider(new ApacheConnectorProvider());
      Client client = ClientBuilder.newClient(clientConfig);
      return client;
    }
  }

  static class  MyCustomException extends RuntimeException { }

}
