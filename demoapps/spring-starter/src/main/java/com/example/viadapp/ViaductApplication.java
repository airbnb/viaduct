package com.example.viadapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.slf4j.LoggerFactory;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.ExecutionResult;
import viaduct.service.api.SchemaId;
import viaduct.service.api.Viaduct;
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory;
import viaduct.service.runtime.SchemaConfiguration;
import viaduct.service.runtime.StandardViaduct;

public class ViaductApplication {

  @SuppressWarnings("deprecation") // withSchemaConfiguration
  public static void main(String[] argv) throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(Level.ERROR);

    Viaduct viaduct =
        new StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory.INSTANCE)
            .withSchemaConfiguration(SchemaConfiguration.Companion.getDEFAULT())
            .build();

    String operationText = argv.length > 0 ? argv[0] : "query { greeting }";

    ExecutionInput executionInput =
        ExecutionInput.Companion.create(operationText, null, Collections.emptyMap(), null);

    ExecutionResult result = viaduct.executeAsync(executionInput, SchemaId.Base.INSTANCE).join();

    System.out.println(
        new ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(result.toSpecification()));
  }
}
