package software.amazon.awssdk.crt.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class HttpHeader {
    private final static Charset UTF8 = StandardCharsets.UTF_8;
    private byte[] name;
    private byte[] value;

    public HttpHeader() {}

    public HttpHeader(String name, String value){
        // TODO: Header validation?
        this.name = name.getBytes(UTF8);
        this.value = value.getBytes(UTF8);
    }

    public String getName() {
        if (name == null) {
            return "";
        }
        return new String(name, UTF8);
    }

    public String getValue() {
        if (value == null) {
            return "";
        }
        return new String(value, UTF8);
    }

    @Override
    public String toString() {
        return getName() + ":" + getValue();
    }
}
