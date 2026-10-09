package viaduct.x.javaapi.codegen;

/** String helpers shared by the generator tests. */
final class TestStrings {

  private TestStrings() {}

  /** Number of non-overlapping occurrences of {@code needle} in {@code haystack}. */
  static int countOccurrences(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle);
        i >= 0;
        i = haystack.indexOf(needle, i + needle.length())) {
      count++;
    }
    return count;
  }
}
