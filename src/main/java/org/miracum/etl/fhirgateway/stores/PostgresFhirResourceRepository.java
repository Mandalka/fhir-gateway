package org.miracum.etl.fhirgateway.stores;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresFhirResourceRepository implements FhirResourceRepository {

  private static final Logger log = LoggerFactory.getLogger(PostgresFhirResourceRepository.class);

  private static final AtomicInteger batchUpdateFailed =
      Metrics.globalRegistry.gauge(
          "fhirgateway.postgres.batchupdate.errors.total", new AtomicInteger(0));

  private final IParser fhirParser;
  private final JdbcTemplate dataSinkTemplate;
  private final RetryTemplate retryTemplate;

  @Autowired
  public PostgresFhirResourceRepository(FhirContext fhirContext, JdbcTemplate dataSinkTemplate) {
    this.fhirParser = fhirContext.newJsonParser();
    this.dataSinkTemplate = dataSinkTemplate;

    this.retryTemplate = new RetryTemplate();

    var fixedBackOffPolicy = new FixedBackOffPolicy();
    fixedBackOffPolicy.setBackOffPeriod(5_000);
    retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

    retryTemplate.setRetryPolicy(new SimpleRetryPolicy(5));

    this.retryTemplate.registerListener(
        new RetryListenerSupport() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            log.warn(
                "Trying to persist data caused error. {} attempt.",
                context.getRetryCount(),
                throwable);
            batchUpdateFailed.incrementAndGet();
          }
        });
  }

  @Override
  public void save(Bundle bundle) {
    var insertValues =
        bundle.getEntry().stream()
            .map(BundleEntryComponent::getResource)
            .sorted(Comparator.comparing(r -> r.getIdElement().getIdPart()))
            .map(
                resource ->
                    new Object[] {
                      resource.getIdElement().getIdPart(),
                      resource.fhirType(),
                      fhirParser.encodeResourceToString(resource)
                    })
            .collect(Collectors.toCollection(ArrayList::new));

    retryTemplate.execute(
        (context) ->
            dataSinkTemplate.batchUpdate(
                "INSERT INTO resources (fhir_id, type, data)"
                    + "VALUES (?, ?, ?::json)"
                    + "ON CONFLICT (fhir_id, type)"
                    + "DO UPDATE set data = EXCLUDED.data, last_updated_at = NOW()",
                insertValues));
  }
}
