package viaduct.java.api.types;

import viaduct.tenant.runtime.support.OffsetCursorCodec;

/**
 * A cursor for offset-based pagination.
 *
 * <p>OffsetCursor wraps an encoded cursor string that represents a position in a paginated list.
 * The cursor format is: {@code Base64("__viaduct:idx:<offset>")}.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.OffsetCursor}. The encode/decode logic
 * lives in the language-neutral {@link OffsetCursorCodec}, shared with the Kotlin Tenant API, so
 * cursors produced by either language are byte-for-byte interchangeable.
 *
 * <ul>
 *   <li>{@link #getValue()} holds the <b>encoded</b> cursor string (e.g. {@code
 *       "X192aWFkdWN0OmlkeDow"})
 *   <li>{@link #toOffset()} <b>decodes</b> the cursor string to get the offset
 *   <li>{@link #fromOffset(int)} <b>creates</b> an OffsetCursor by encoding the offset
 * </ul>
 */
public final class OffsetCursor {
  private final String value;

  public OffsetCursor(String value) {
    this.value = value;
  }

  /** Returns the encoded cursor string (Base64 encoded). */
  public String getValue() {
    return value;
  }

  /**
   * Decodes this cursor to get the offset value.
   *
   * @return the decoded offset
   * @throws IllegalArgumentException if the cursor is invalid, malformed, or uses an unknown format
   */
  public int toOffset() {
    return OffsetCursorCodec.INSTANCE.decode(value);
  }

  /**
   * Creates an OffsetCursor from an offset value. Encodes the offset into the cursor string format.
   *
   * @param offset the zero-based offset position
   * @return an OffsetCursor containing the encoded cursor string
   * @throws IllegalArgumentException if offset is negative
   */
  public static OffsetCursor fromOffset(int offset) {
    return new OffsetCursor(OffsetCursorCodec.INSTANCE.encode(offset));
  }

  /**
   * Checks if a cursor string is valid without throwing.
   *
   * @param cursor the cursor string to validate
   * @return true if the cursor is valid and decodable
   */
  public static boolean isValid(String cursor) {
    return OffsetCursorCodec.INSTANCE.isValid(cursor);
  }
}
