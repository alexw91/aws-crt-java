package software.amazon.awssdk.crt.utils;

public interface AwsCrtInputStreamEvent {

    public void notifyWriteSpaceAvailable(int availableWriteSpace);
    public void notifyStreamClosed();

}
