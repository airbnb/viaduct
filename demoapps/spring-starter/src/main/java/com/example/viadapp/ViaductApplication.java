package com.example.viadapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.slf4j.LoggerFactory;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.bridge.JavaTenantAPIBootstrapper;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.ExecutionResult;
import viaduct.service.api.SchemaId;
import viaduct.service.api.Viaduct;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.runtime.SchemaConfiguration;
import viaduct.service.runtime.StandardViaduct;

public class ViaductApplication {

  private static final String TENANT_PACKAGE = "com.example.viadapp.resolvers";

  public static void main(String[] argv) throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(Level.ERROR);

    DefaultJavaResolverClassFinder classFinder =
        new DefaultJavaResolverClassFinder(TENANT_PACKAGE, TENANT_PACKAGE);
    JavaModuleBootstrapper bootstrapper =
        new JavaModuleBootstrapper(classFinder, CodeInjector.Companion.getNaive());

    TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> bootstrapperBuilder =
        () -> new JavaTenantAPIBootstrapper(List.of(bootstrapper));

    Viaduct viaduct =
        new StandardViaduct.Builder()
            .withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
            .withSchemaConfiguration(SchemaConfiguration.Companion.getDEFAULT())
            .build();

    String operationText = argv.length > 0 ? argv[0] : "query { greeting }";

    ExecutionInput executionInput =
        ExecutionInput.Companion.create(operationText, null, Collections.emptyMap(), null);

    ExecutionResult result = viaduct.execute(executionInput, SchemaId.Full);

    System.out.println(
        new ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(result.toSpecification()));
  }
}
