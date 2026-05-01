package com.example.viadapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
public class ViaductApplicationTest {
  private final PrintStream originalOut = System.out;
  private ByteArrayOutputStream outputStream;

  @BeforeEach
  void setUp() {
    outputStream = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outputStream));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private Map<String, Object> getJsonOutput() throws Exception {
    String output = outputStream.toString().trim();
    return new ObjectMapper().readValue(output, Map.class);
  }

  @Test
  void testMainWithNoArguments() throws Exception {
    ViaductApplication.main(new String[] {});

    Map<String, Object> result = getJsonOutput();
    Map<String, Object> data = (Map<String, Object>) result.get("data");

    assertEquals("Hello, World!", data.get("greeting"));
    assertNull(result.get("errors"));
  }

  @Test
  void testMainWithAuthorQuery() throws Exception {
    ViaductApplication.main(new String[] {"{ author }"});

    Map<String, Object> result = getJsonOutput();
    Map<String, Object> data = (Map<String, Object>) result.get("data");

    assertEquals("Brian Kernighan", data.get("author"));
    assertNull(result.get("errors"));
  }

  @Test
  void testMainWithGreetingQuery() throws Exception {
    ViaductApplication.main(new String[] {"{ greeting }"});

    Map<String, Object> result = getJsonOutput();
    Map<String, Object> data = (Map<String, Object>) result.get("data");

    assertEquals("Hello, World!", data.get("greeting"));
    assertNull(result.get("errors"));
  }

  @Test
  void testMainWithBothFields() throws Exception {
    ViaductApplication.main(new String[] {"query {\n    greeting\n    author\n}"});

    Map<String, Object> result = getJsonOutput();
    Map<String, Object> data = (Map<String, Object>) result.get("data");

    assertEquals("Hello, World!", data.get("greeting"));
    assertEquals("Brian Kernighan", data.get("author"));
    assertNull(result.get("errors"));
  }

  @Test
  void testMainWithInvalidField() throws Exception {
    ViaductApplication.main(new String[] {"{ invalidField }"});

    Map<String, Object> result = getJsonOutput();
    List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");

    assertNull(result.get("data"));
    assertEquals(1, errors.size());

    Map<String, Object> error = errors.get(0);
    String message = (String) error.get("message");
    assertTrue(message.contains("Field 'invalidField' in type 'Query' is undefined"));

    Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");
    assertEquals("ValidationError", extensions.get("classification"));
  }

  @Test
  void testMainWithInvalidSyntax() throws Exception {
    ViaductApplication.main(new String[] {"invalid syntax"});

    Map<String, Object> result = getJsonOutput();
    List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");

    assertNull(result.get("data"));
    assertEquals(1, errors.size());

    Map<String, Object> error = errors.get(0);
    String message = (String) error.get("message");
    assertTrue(message.contains("Invalid syntax"));

    Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");
    assertEquals("InvalidSyntax", extensions.get("classification"));
  }

  @Test
  void testMainWithMalformedQuery() throws Exception {
    ViaductApplication.main(new String[] {"{ greeting"});

    Map<String, Object> result = getJsonOutput();
    List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");

    assertNull(result.get("data"));
    assertEquals(1, errors.size());

    Map<String, Object> error = errors.get(0);
    String message = (String) error.get("message");
    assertTrue(message.contains("Invalid syntax") || message.contains("syntax"));
  }
}
