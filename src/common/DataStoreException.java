package common;

/** Error no recuperable al leer o escribir el estado persistente del scraper. */
public final class DataStoreException extends RuntimeException {

    public DataStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
