package dev.pointledger.di;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifies the {@code LongSupplier} that stamps each new command with epoch milliseconds.
 * Production binds it to the system clock; tests override it with a controllable clock
 * (see {@code TestMain.diClockOverride}).
 */
@BindingAnnotation
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface WallClock {}
