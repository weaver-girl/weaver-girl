package com.github.cc11001100.weavergirl.core.release;

import java.util.Objects;

/**
 * Remote version descriptor fetched by {@link UpdateChecker}.
 *
 * <p>Expected JSON shape from the update endpoint:
 *
 * <pre>
 * {"version":"1.2.0","downloadUrl":"https://.../weaver-girl-agent-1.2.0.jar",
 *  "sha256":"&lt;hex&gt;","mandatory":false,"releaseNotes":"..."}
 * </pre>
 *
 * <p>Only {@code version} is required; missing optional fields degrade gracefully (no download URL
 * means check-only mode).
 *
 * @since 1.10.0
 */
public final class VersionInfo {

  private final String version;
  private final String downloadUrl;
  private final String sha256;
  private final boolean mandatory;
  private final String releaseNotes;

  private VersionInfo(Builder builder) {
    this.version = builder.version;
    this.downloadUrl = builder.downloadUrl;
    this.sha256 = builder.sha256;
    this.mandatory = builder.mandatory;
    this.releaseNotes = builder.releaseNotes;
  }

  public String getVersion() {
    return version;
  }

  public String getDownloadUrl() {
    return downloadUrl;
  }

  public String getSha256() {
    return sha256;
  }

  public boolean isMandatory() {
    return mandatory;
  }

  public String getReleaseNotes() {
    return releaseNotes;
  }

  /** True when a download URL is present, so the artifact can be staged. */
  public boolean isDownloadable() {
    return downloadUrl != null && !downloadUrl.trim().isEmpty();
  }

  /**
   * Parse a {@link VersionInfo} from a JSON object string.
   *
   * <p>Dependency-free minimal parser: extracts the known string/boolean fields and ignores
   * everything else. Returns null when no usable {@code version} field is present.
   *
   * @param json the response body, may be null
   * @return parsed info, or null if unparseable
   */
  public static VersionInfo parseJson(String json) {
    if (json == null || json.trim().isEmpty()) {
      return null;
    }
    String version = extractString(json, "version");
    if (version == null || version.isEmpty()) {
      return null;
    }
    return new Builder()
        .version(version)
        .downloadUrl(extractString(json, "downloadUrl"))
        .sha256(extractString(json, "sha256"))
        .mandatory("true".equalsIgnoreCase(extractLiteral(json, "mandatory")))
        .releaseNotes(extractString(json, "releaseNotes"))
        .build();
  }

  private static String extractString(String json, String key) {
    String marker = "\"" + key + "\"";
    int idx = json.indexOf(marker);
    if (idx < 0) {
      return null;
    }
    int colon = json.indexOf(':', idx + marker.length());
    if (colon < 0) {
      return null;
    }
    int start = colon + 1;
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    if (start >= json.length() || json.charAt(start) != '"') {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '\\' && i + 1 < json.length()) {
        sb.append(json.charAt(i + 1));
        i++;
      } else if (c == '"') {
        return sb.toString();
      } else {
        sb.append(c);
      }
    }
    return null;
  }

  private static String extractLiteral(String json, String key) {
    String marker = "\"" + key + "\"";
    int idx = json.indexOf(marker);
    if (idx < 0) {
      return null;
    }
    int colon = json.indexOf(':', idx + marker.length());
    if (colon < 0) {
      return null;
    }
    int start = colon + 1;
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length()
        && json.charAt(end) != ','
        && json.charAt(end) != '}'
        && !Character.isWhitespace(json.charAt(end))) {
      end++;
    }
    return start < end ? json.substring(start, end) : null;
  }

  /**
   * Compare two semver-like version strings.
   *
   * @return negative if v1 &lt; v2, zero if equal, positive if v1 &gt; v2
   */
  public static int compareVersions(String v1, String v2) {
    if (v1 == null) {
      v1 = "0.0.0";
    }
    if (v2 == null) {
      v2 = "0.0.0";
    }
    String[] parts1 = v1.split("\\.");
    String[] parts2 = v2.split("\\.");
    int maxLen = Math.max(parts1.length, parts2.length);
    for (int i = 0; i < maxLen; i++) {
      int p1 = parsePart(parts1, i);
      int p2 = parsePart(parts2, i);
      if (p1 != p2) {
        return Integer.compare(p1, p2);
      }
    }
    // Numeric parts equal: a bare release outranks a pre-release
    // (1.0.0 > 1.0.0-SNAPSHOT).
    boolean pre1 = v1.contains("-");
    boolean pre2 = v2.contains("-");
    if (pre1 != pre2) {
      return pre1 ? -1 : 1;
    }
    return 0;
  }

  private static int parsePart(String[] parts, int index) {
    if (index >= parts.length) {
      return 0;
    }
    try {
      String part = parts[index].split("-")[0].split("\\+")[0];
      return Integer.parseInt(part);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** True when {@code candidate} is strictly newer than {@code current}. */
  public static boolean isNewerThan(String candidate, String current) {
    return compareVersions(candidate, current) > 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VersionInfo)) {
      return false;
    }
    VersionInfo that = (VersionInfo) o;
    return mandatory == that.mandatory
        && Objects.equals(version, that.version)
        && Objects.equals(downloadUrl, that.downloadUrl)
        && Objects.equals(sha256, that.sha256)
        && Objects.equals(releaseNotes, that.releaseNotes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, downloadUrl, sha256, mandatory, releaseNotes);
  }

  @Override
  public String toString() {
    return "VersionInfo{version='"
        + version
        + "'"
        + (downloadUrl != null ? ", downloadUrl='" + downloadUrl + "'" : "")
        + ", mandatory="
        + mandatory
        + "}";
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String version;
    private String downloadUrl;
    private String sha256;
    private boolean mandatory;
    private String releaseNotes;

    public Builder version(String v) {
      this.version = v;
      return this;
    }

    public Builder downloadUrl(String v) {
      this.downloadUrl = v;
      return this;
    }

    public Builder sha256(String v) {
      this.sha256 = v;
      return this;
    }

    public Builder mandatory(boolean v) {
      this.mandatory = v;
      return this;
    }

    public Builder releaseNotes(String v) {
      this.releaseNotes = v;
      return this;
    }

    public VersionInfo build() {
      if (version == null || version.trim().isEmpty()) {
        throw new IllegalStateException("version must be set");
      }
      return new VersionInfo(this);
    }
  }
}
