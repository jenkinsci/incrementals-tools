package io.jenkins.tools.incrementals.maven;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DetectIndentTest {

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        { "abc", 0, ' ' }, { "  abc", 2, ' ' }, { "    abc", 4, ' ' }, { "\t\tabc", 2, '\t' }, { "\tabc", 1, '\t' }
    });
  }

  private String input;

  private int size;

  private char type;

  public DetectIndentTest(String input, int size, char type) {
    this.input = input;
    this.size = size;
    this.type = type;
  }

  @Test
  public void detectIndentSize() {
    DetectIndent detectIndent = new DetectIndent();
    DetectIndent.Indent indent = detectIndent.detect(input);
    Assert.assertEquals(size, indent.size);
    Assert.assertEquals(type, indent.type);
  }
}
