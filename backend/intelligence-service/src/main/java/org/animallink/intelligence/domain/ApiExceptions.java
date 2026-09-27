package org.animallink.intelligence.domain;

public final class ApiExceptions {
    private ApiExceptions() {}

    public static class Unauthorized extends RuntimeException { public Unauthorized(String m) { super(m); } }
    public static class Forbidden extends RuntimeException { public Forbidden(String m) { super(m); } }
    public static class NotFound extends RuntimeException { public NotFound(String m) { super(m); } }
    public static class Conflict extends RuntimeException { public Conflict(String m) { super(m); } }
    public static class DependencyUnavailable extends RuntimeException { public DependencyUnavailable(String m) { super(m); } }
    public static class MediaNotFound extends RuntimeException { public MediaNotFound(String m) { super(m); } }
    public static class UnsupportedMedia extends RuntimeException { public UnsupportedMedia(String m) { super(m); } }
    public static class ProviderTimeout extends RuntimeException { public ProviderTimeout(String m, Throwable c) { super(m, c); } }
    public static class ProviderUnavailable extends RuntimeException { public ProviderUnavailable(String m) { super(m); } public ProviderUnavailable(String m, Throwable c) { super(m, c); } }
    public static class InvalidModelResponse extends RuntimeException { public InvalidModelResponse(String m) { super(m); } public InvalidModelResponse(String m, Throwable c) { super(m, c); } }
    public static class CandidateServiceUnavailable extends RuntimeException { public CandidateServiceUnavailable(String m, Throwable c) { super(m, c); } }
    public static class EmbeddingTimeout extends RuntimeException { public EmbeddingTimeout(String m, Throwable c) { super(m, c); } }
    public static class EmbeddingUnavailable extends RuntimeException { public EmbeddingUnavailable(String m) { super(m); } public EmbeddingUnavailable(String m, Throwable c) { super(m, c); } }
    public static class InvalidEmbeddingVector extends RuntimeException { public InvalidEmbeddingVector(String m) { super(m); } }
    public static class TaskNotSucceeded extends RuntimeException { public TaskNotSucceeded(String m) { super(m); } }
    public static class TaskNotConfirmed extends RuntimeException { public TaskNotConfirmed(String m) { super(m); } }
    public static class TaskNoLongerValid extends RuntimeException { public TaskNoLongerValid(String m) { super(m); } }
    public static class AlreadyFinalized extends RuntimeException { public AlreadyFinalized(String m) { super(m); } }
    public static class SelectedAnimalNotCandidate extends RuntimeException { public SelectedAnimalNotCandidate(String m) { super(m); } }
    public static class FinalizationServiceUnavailable extends RuntimeException {
        public FinalizationServiceUnavailable(String m, Throwable c) { super(m, c); }
    }
    public static class AnimalFinalizationRejected extends RuntimeException {
        private final int status;
        private final String code;

        public AnimalFinalizationRejected(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
