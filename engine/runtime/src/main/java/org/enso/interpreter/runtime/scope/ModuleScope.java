package org.enso.interpreter.runtime.scope;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import org.enso.interpreter.Constants;
import org.enso.interpreter.Language;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.Vector;

import java.util.*;

/** A representation of Enso's per-file top-level scope. */
@ExportLibrary(InteropLibrary.class)
public class ModuleScope implements TruffleObject {
  private final AtomConstructor associatedType;
  private final Map<String, AtomConstructor> constructors = new HashMap<>();
  private final Map<AtomConstructor, Map<String, Function>> methods = new HashMap<>();
  private final Set<ModuleScope> imports = new HashSet<>();

  /**
   * Creates a new object of this class.
   *
   * @param name the name of the newly created module.
   */
  public ModuleScope(String name) {
    associatedType = new AtomConstructor(name, this).initializeFields();
  }

  /**
   * Adds an Atom constructor definition to the module scope.
   *
   * @param constructor the constructor to register
   */
  public void registerConstructor(AtomConstructor constructor) {
    constructors.put(constructor.getName(), constructor);
  }

  /** @return the associated type of this module. */
  public AtomConstructor getAssociatedType() {
    return associatedType;
  }

  /**
   * Looks up a constructor in the module scope.
   *
   * @param name the name of the module binding
   * @return the Atom constructor associated with {@code name}, or {@link Optional#empty()}
   */
  public Optional<AtomConstructor> getConstructor(String name) {
    if (associatedType.getName().equals(name)) {
      return Optional.of(associatedType);
    }
    Optional<AtomConstructor> locallyDefined = Optional.ofNullable(this.constructors.get(name));
    if (locallyDefined.isPresent()) return locallyDefined;
    return imports.stream()
        .map(scope -> scope.getConstructor(name))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst();
  }

  /**
   * Returns a map of methods defined in this module for a given constructor.
   *
   * @param cons the constructor for which method map is requested
   * @return a map containing all the defined methods by name
   */
  private Map<String, Function> ensureMethodMapFor(AtomConstructor cons) {
    return methods.computeIfAbsent(cons, k -> new HashMap<>());
  }

  private Map<String, Function> getMethodMapFor(AtomConstructor cons) {
    Map<String, Function> result = methods.get(cons);
    if (result == null) {
      return new HashMap<>();
    }
    return result;
  }

  /**
   * Registers a method defined for a given type.
   *
   * @param atom type the method was defined for
   * @param method method name
   * @param function the {@link Function} associated with this definition
   */
  public void registerMethod(AtomConstructor atom, String method, Function function) {
    ensureMethodMapFor(atom).put(method, function);
  }

  /**
   * Looks up the definition for a given type and method name.
   *
   * <p>The resolution algorithm is first looking for methods defined at the constructor definition
   * site (i.e. non-overloads), then looks for methods defined in this scope and finally tries to
   * resolve the method in all dependencies of this module.
   *
   * @param atom type to lookup the method for.
   * @param name the method name.
   * @return the matching method definition or null if not found.
   */
  @CompilerDirectives.TruffleBoundary
  public Function lookupMethodDefinition(AtomConstructor atom, String name) {
    Function definedWithAtom = atom.getDefinitionScope().getMethodMapFor(atom).get(name);
    if (definedWithAtom != null) {
      return definedWithAtom;
    }
    Function definedHere = getMethodMapFor(atom).get(name);
    if (definedHere != null) {
      return definedHere;
    }
    return imports.stream()
        .map(scope -> scope.getMethodMapFor(atom).get(name))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Adds a dependency for this module.
   *
   * @param scope the scope of the newly added dependency
   */
  public void addImport(ModuleScope scope) {
    imports.add(scope);
  }

  private static class PolyglotKeys {
    private static final String ASSOCIATED_CONSTRUCTOR = "get_associated_constructor";
    private static final String METHODS = "get_method";
    private static final String CONSTRUCTORS = "get_constructor";
    private static final String PATCH = "patch";
  }

  /**
   * Handles member invocations through the polyglot API.
   *
   * <p>The exposed members are:
   * <li>{@code get_method(AtomConstructor, String)}
   * <li>{@code get_constructor(String)}
   * <li>{@code patch(String)}
   * <li>{@code get_associated_constructor()}
   */
  @ExportMessage
  abstract static class InvokeMember {
    // TODO[MK]: These functions are 90% typechecks. We should consider refactoring options,
    // possibly code generation.
    private static Function getMethod(ModuleScope scope, Object[] args)
        throws ArityException, UnsupportedTypeException {
      if (args.length != 2) {
        throw ArityException.create(2, args.length);
      }
      if (!(args[0] instanceof AtomConstructor)) {
        throw UnsupportedTypeException.create(
            args, "The first argument must be an atom constructor.");
      }
      AtomConstructor cons = (AtomConstructor) args[0];
      if (!(args[1] instanceof String)) {
        throw UnsupportedTypeException.create(args, "The second argument must be a String.");
      }
      String name = (String) args[1];
      return scope.methods.get(cons).get(name);
    }

    private static AtomConstructor getConstructor(ModuleScope scope, Object[] args)
        throws ArityException, UnsupportedTypeException {
      if (args.length != 1) {
        throw ArityException.create(1, args.length);
      }
      if (!(args[0] instanceof String)) {
        throw UnsupportedTypeException.create(args, "The argument must be a String.");
      }
      String name = (String) args[0];
      return scope.constructors.get(name);
    }

    private static ModuleScope patch(ModuleScope scope, Object[] args, Context context)
        throws ArityException, UnsupportedTypeException {
      if (args.length != 1) {
        throw ArityException.create(1, args.length);
      }
      if (!(args[0] instanceof String)) {
        throw UnsupportedTypeException.create(args, "The argument must be a String.");
      }
      String sourceString = (String) args[0];
      Source source =
          Source.newBuilder(Constants.LANGUAGE_ID, sourceString, scope.associatedType.getName())
              .build();
      context.compiler().run(source, scope);
      return scope;
    }

    private static AtomConstructor getAssociatedConstructor(ModuleScope scope, Object[] args)
        throws ArityException {
      if (args != null && args.length != 0) {
        throw ArityException.create(0, args.length);
      }
      return scope.associatedType;
    }

    @Specialization
    static Object doInvoke(
        ModuleScope scope,
        String member,
        Object[] arguments,
        @CachedContext(Language.class) TruffleLanguage.ContextReference<Context> contextRef)
        throws UnknownIdentifierException, ArityException, UnsupportedTypeException {
      switch (member) {
        case PolyglotKeys.METHODS:
          return getMethod(scope, arguments);
        case PolyglotKeys.CONSTRUCTORS:
          return getConstructor(scope, arguments);
        case PolyglotKeys.PATCH:
          return patch(scope, arguments, contextRef.get());
        case PolyglotKeys.ASSOCIATED_CONSTRUCTOR:
          return getAssociatedConstructor(scope, arguments);
        default:
          throw UnknownIdentifierException.create(member);
      }
    }
  }

  /**
   * Marks the object as having members for the purposes of the polyglot API.
   *
   * @return {@code true}
   */
  @ExportMessage
  boolean hasMembers() {
    return true;
  }

  /**
   * Exposes a member method validity check for the polyglot API.
   *
   * @param member the member to check
   * @return {@code true} if the member is supported, {@code false} otherwise.
   */
  @ExportMessage
  boolean isMemberInvocable(String member) {
    return member.equals(PolyglotKeys.METHODS)
        || member.equals(PolyglotKeys.CONSTRUCTORS)
        || member.equals(PolyglotKeys.PATCH)
        || member.equals(PolyglotKeys.ASSOCIATED_CONSTRUCTOR);
  }

  /**
   * Returns a collection of all the supported members in this scope for the polyglot API.
   *
   * @param includeInternal ignored.
   * @return a collection of all the member names.
   */
  @ExportMessage
  Object getMembers(boolean includeInternal) {
    return new Vector(
        PolyglotKeys.METHODS,
        PolyglotKeys.CONSTRUCTORS,
        PolyglotKeys.PATCH,
        PolyglotKeys.ASSOCIATED_CONSTRUCTOR);
  }
}
