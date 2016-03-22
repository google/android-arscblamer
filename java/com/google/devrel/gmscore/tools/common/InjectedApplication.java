/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devrel.gmscore.tools.common;

import com.google.common.base.Throwables;
import com.google.inject.Binder;
import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.ProvisionException;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/** A helper class for running a Guice injected application with some flag validation added. */
public class InjectedApplication {

  private final Injector injector;

  private InjectedApplication(Module... modules) {
    injector = Guice.createInjector(modules);
  }

  /**
   * Gets an instance from Guice for the provided class. Any missing flags or bindings will be
   * printed in the error message if there was a problem retrieving the class instance.
   *
   * @param cls The class type to retrieve from the Guice injector.
   * @param <T> The type of the class that is being returned.
   * @return The class instance retrieved from Guice.
   */
  public <T> T get(Class<T> cls) {
    try {
      return injector.getInstance(cls);
    } catch (ProvisionException e) {
      System.err.println("Could not start application:");
      for (Message msg : e.getErrorMessages()) {
        System.err.println("  " + msg.toString());
      }
      System.exit(1);
    }

    throw new IllegalStateException("Did not get an instance, and did not get an exception?");
  }

  /**
   * Allows for the creation of {@link InjectedApplication} with Guice modules and flag parameters.
   */
  public static class Builder {

    private final Set<Class<?>> parameters = new HashSet<>();
    private final List<Module> modules = new ArrayList<>();
    private final String[] arguments;

    /** Creates a builder with the given CLI {@code arguments}. */
    public Builder(String... arguments) {
      this.arguments = arguments;
    }

    /** Adds class(es) containing one or more fields with a {@link Parameter} annotation. */
    public Builder withParameter(Class<?>... parameters) {
      this.parameters.addAll(Arrays.asList(parameters));
      return this;
    }

    /** Adds module(s) to the {@link InjectedApplication}. */
    public Builder withModule(Module... modules) {
      this.modules.addAll(Arrays.asList(modules));
      return this;
    }

    /** Builds an {@link InjectedApplication}. */
    public InjectedApplication build() {
      // This works by providing all flag parameters as the first module. Alternatively, a module
      // could be created for each parameter.
      modules.add(0, new Module() {
        @Override
        public void configure(Binder binder) {
          Map<Class<?>, Object> modules = new HashMap<>();
          for (Class<?> parameter : parameters) {
            try {
              modules.put(parameter, parameter.newInstance());
            } catch (InstantiationException | IllegalAccessException e) {
              throw Throwables.propagate(e);
            }
          }
          new JCommander(modules.values(), arguments);
          for (Map.Entry<Class<?>, Object> entry : modules.entrySet()) {
            bindFlagModule(binder, entry.getKey(), entry.getValue());
          }
        }
      });
      return new InjectedApplication(modules.toArray(new Module[modules.size()]));
    }

    /**
     * Binds all fields with {@link BindingAnnotation}-annotated annotations in {@code type} to
     * {@code binder}.
     */
    private <T> void bindFlagModule(Binder binder, Class<T> type, Object module) {
      try {
        for (Field field : type.getDeclaredFields()) {
          bindFieldAnnotations(binder, field, field.getType(), module);
        }
        binder.bind(type).toInstance(type.cast(module));
      } catch (IllegalAccessException e) {
        throw Throwables.propagate(e);
      }
    }

    private <T> void bindFieldAnnotations(Binder binder, Field field, Class<T> fieldType,
        Object object) throws IllegalAccessException {
      for (Annotation annotation : getBindingAnnotations(field)) {
        field.setAccessible(true);  // Needed to allow immutable flags.
        bindAnnotation(binder, fieldType, fieldType.cast(field.get(object)), annotation);
      }
    }

    private <T, U extends T> void bindAnnotation(Binder binder, Class<T> type, @Nullable U object,
        Annotation annotation) {
      if (object != null && !type.isInstance(object)) {
        throw new RuntimeException("Impossible state while binding flag annotations.");
      }
      binder.bind(type).annotatedWith(annotation).toProvider(Providers.of(object));
    }

    private Collection<Annotation> getBindingAnnotations(Field field) {
      List<Annotation> result = new ArrayList<>();
      for (Annotation annotation : field.getAnnotations()) {
        if (annotation.annotationType().isAnnotationPresent(BindingAnnotation.class)) {
          result.add(annotation);
        }
      }
      return result;
    }
  }
}
