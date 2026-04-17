package io.ib67.packsync.data.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class InstantAdapter extends TypeAdapter<Instant> {

    @Override
    public void write(JsonWriter out, Instant value) throws IOException {
        out.value(DateTimeFormatter.ISO_INSTANT.format(value));
    }

    @Override
    public Instant read(JsonReader in) throws IOException {
        var formatted = in.nextString();
        return (Instant) DateTimeFormatter.ISO_INSTANT.parse(formatted);
    }
}
