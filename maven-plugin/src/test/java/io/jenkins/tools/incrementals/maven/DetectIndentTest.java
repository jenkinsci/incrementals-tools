package io.jenkins.tools.incrementals.maven;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DetectIndentTest {

  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        { "abc", 0, ' ' }, { "  abc", 2, ' ' }, { "    abc", 4, ' ' }, { "\t\tabc", 2, '\t' }, { "\tabc", 1, '\t' }
    });
  }

  @ParameterizedTest
  @MethodSource("data")
  public void detectIndentSize(String input, int size, char type) {
    DetectIndent detectIndent = new DetectIndent();
    DetectIndent.Indent indent = detectIndent.detect(input);
    assertEquals(size, indent.getSize());
    assertEquals(type, indent.getType());
  }
}
