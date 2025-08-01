package io.jenkins.tools.incrementals.maven;

public class DetectIndent {

  public Indent detect(String input) {
    if (input != null && !input.isEmpty()) {
      int size = 0;
      char indent = ' ';
      String[] inputs = input.split("(\r\n|\r|\n)");
      for (String line: inputs) {
        for (int i = 0; i < line.length(); i++) {
          switch(line.charAt(i))
          {
            case '\t':
              size++;
              indent = '\t';
              break;
            case ' ':
              indent = ' ';
              size++;
              break;
            default:
              if (size == 0) continue;
              return new Indent(size, indent);
          }
        }
      }
    }
    return new Indent();
  }

  public static class Indent {
    int size = 0;
    char type = ' ';

    public Indent() {
    }

    public Indent(int size, char indent) {
      this.size = size;
      this.type = indent;
    }

    public String getIndent() {
      return String.valueOf(type).repeat(size);
    }

    public int getSize() {
      return size;
    }

    public void setSize(int size) {
      this.size = size;
    }

    public char getType() {
      return type;
    }

    public void setType(char type) {
      this.type = type;
    }
  }
}
