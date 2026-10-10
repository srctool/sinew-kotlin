package com.srctool.sinew.models

/** Marks a domain model: what the app needs, in business language. Domain models are `data class`es. */
public interface Domain

/** What the backend sends, shaped like its JSON. Private to its data module; it exists only to be mapped. */
public abstract class Remote<out D : Domain> {
    public abstract fun toDomain(): D
}

/** What's stored on the device. Private to its data module; it exists only to be mapped. */
public abstract class Entity<out D : Domain> {
    public abstract fun toDomain(): D
}
