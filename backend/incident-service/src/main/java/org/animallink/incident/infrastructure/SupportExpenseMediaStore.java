package org.animallink.incident.infrastructure;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SupportExpenseMediaStore {
  public static final long MAX_IMAGE_BYTES = 10_485_760L;
  public static final long MAX_TOTAL_BYTES = 31_457_280L;
  public static final int URL_EXPIRY_SECONDS = 600;
  public static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

  private static final Logger log = LoggerFactory.getLogger(SupportExpenseMediaStore.class);
  private final MinioClient minio;
  private final String bucket;

  public SupportExpenseMediaStore(MinioClient minio, @Value("${animallink.minio.bucket}") String bucket) {
    this.minio = minio;
    this.bucket = bucket;
  }

  public UploadIntent issueUpload(String userId, String fileName, String contentType) {
    String type = normalizeType(contentType);
    if (!ALLOWED_TYPES.contains(type)) throw new EvidenceInvalid();
    String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    String key = "incident-input/" + userId + "/support-expenses/" + UUID.randomUUID() + "-" + safeName;
    try {
      String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .method(Method.PUT).bucket(bucket).object(key).expiry(URL_EXPIRY_SECONDS).build());
      return new UploadIntent(key, url, URL_EXPIRY_SECONDS);
    } catch (Exception ex) {
      throw new StorageUnavailable(ex);
    }
  }

  public List<Formalized> formalizeStrict(String userId, String campaignId, String expenseId, List<Candidate> candidates) {
    List<Formalized> copied = new ArrayList<>();
    try {
      for (Candidate candidate : candidates) {
        String type = normalizeType(candidate.contentType());
        if (!candidate.temporaryObjectKey().startsWith("incident-input/" + userId + "/support-expenses/")
            || !ALLOWED_TYPES.contains(type)) throw new EvidenceInvalid();

        var stat = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(candidate.temporaryObjectKey()).build());
        String storedType = normalizeType(stat.contentType());
        if (stat.size() != candidate.sizeBytes() || !storedType.equals(type) || stat.size() < 1 || stat.size() > MAX_IMAGE_BYTES) {
          throw new EvidenceInvalid();
        }

        byte[] bytes;
        try (InputStream stream = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(candidate.temporaryObjectKey()).build())) {
          bytes = stream.readNBytes((int) MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length != stat.size() || bytes.length > MAX_IMAGE_BYTES || !validImage(type, bytes)) throw new EvidenceInvalid();

        String target = "support-expenses/" + campaignId + "/" + expenseId + "/" + candidate.mediaId() + extension(type);
        minio.copyObject(CopyObjectArgs.builder().bucket(bucket).object(target)
            .source(CopySource.builder().bucket(bucket).object(candidate.temporaryObjectKey()).build()).build());
        copied.add(new Formalized(candidate.mediaId(), target, type, candidate.sizeBytes(), candidate.sortOrder()));
      }
      return List.copyOf(copied);
    } catch (EvidenceInvalid ex) {
      removeFormalized(copied);
      throw ex;
    } catch (Exception ex) {
      removeFormalized(copied);
      throw new StorageUnavailable(ex);
    }
  }

  public void removeFormalized(List<Formalized> media) {
    int failed = 0;
    for (Formalized item : media) {
      try {
        minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.objectKey()).build());
      } catch (Exception ex) {
        failed++;
      }
    }
    if (failed > 0) log.warn("Support Expense orphan cleanup incomplete: failedCount={}", failed);
  }

  public AccessUrl readUrl(String objectKey) {
    try {
      String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .method(Method.GET).bucket(bucket).object(objectKey).expiry(URL_EXPIRY_SECONDS).build());
      return new AccessUrl(url, URL_EXPIRY_SECONDS);
    } catch (Exception ex) {
      throw new StorageUnavailable(ex);
    }
  }

  private static String normalizeType(String value) {
    if (value == null) return "";
    return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  private static String extension(String type) {
    return switch (type) {
      case "image/jpeg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> throw new EvidenceInvalid();
    };
  }

  private static boolean validImage(String type, byte[] bytes) {
    if (!matchesMagic(type, bytes)) return false;
    if ("image/webp".equals(type)) return validWebp(bytes);
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      return image != null && image.getWidth() > 0 && image.getHeight() > 0;
    } catch (Exception ex) {
      return false;
    }
  }

  private static boolean matchesMagic(String type, byte[] bytes) {
    return switch (type) {
      case "image/jpeg" -> bytes.length >= 3 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff;
      case "image/png" -> bytes.length >= 8 && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
          && unsigned(bytes[4]) == 0x0d && unsigned(bytes[5]) == 0x0a && unsigned(bytes[6]) == 0x1a && unsigned(bytes[7]) == 0x0a;
      case "image/webp" -> bytes.length >= 20 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
      default -> false;
    };
  }

  private static boolean validWebp(byte[] bytes) {
    if (bytes.length < 20 || little32(bytes, 4) + 8L > bytes.length) return false;
    int offset = 12;
    while (offset + 8 <= bytes.length) {
      long length = little32(bytes, offset + 4);
      long end = (long) offset + 8 + length;
      if (length < 0 || end > bytes.length) return false;
      int data = offset + 8;
      if (ascii(bytes, offset, "VP8 ") && length >= 10 && unsigned(bytes[data + 3]) == 0x9d
          && unsigned(bytes[data + 4]) == 0x01 && unsigned(bytes[data + 5]) == 0x2a) {
        return ((unsigned(bytes[data + 7]) << 8 | unsigned(bytes[data + 6])) & 0x3fff) > 0
            && ((unsigned(bytes[data + 9]) << 8 | unsigned(bytes[data + 8])) & 0x3fff) > 0;
      }
      if (ascii(bytes, offset, "VP8L") && length >= 5 && unsigned(bytes[data]) == 0x2f) {
        int width = 1 + unsigned(bytes[data + 1]) + ((unsigned(bytes[data + 2]) & 0x3f) << 8);
        int height = 1 + ((unsigned(bytes[data + 2]) >> 6) | (unsigned(bytes[data + 3]) << 2)
            | ((unsigned(bytes[data + 4]) & 0x0f) << 10));
        return width > 0 && height > 0;
      }
      offset = (int) (end + (length & 1));
    }
    return false;
  }

  private static boolean ascii(byte[] bytes, int offset, String text) {
    if (offset < 0 || offset + text.length() > bytes.length) return false;
    for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != (byte) text.charAt(i)) return false;
    return true;
  }

  private static long little32(byte[] bytes, int offset) {
    if (offset < 0 || offset + 4 > bytes.length) return -1;
    return (long) unsigned(bytes[offset]) | ((long) unsigned(bytes[offset + 1]) << 8)
        | ((long) unsigned(bytes[offset + 2]) << 16) | ((long) unsigned(bytes[offset + 3]) << 24);
  }

  private static int unsigned(byte value) { return value & 0xff; }

  public record UploadIntent(String temporaryObjectKey, String uploadUrl, int expiresInSeconds) {}
  public record Candidate(String mediaId, String temporaryObjectKey, String contentType, long sizeBytes, int sortOrder) {}
  public record Formalized(String mediaId, String objectKey, String contentType, long sizeBytes, int sortOrder) {}
  public record AccessUrl(String readUrl, int expiresInSeconds) {}
  public static class EvidenceInvalid extends RuntimeException {}
  public static class StorageUnavailable extends RuntimeException {
    public StorageUnavailable(Throwable cause) { super(cause); }
  }
}
