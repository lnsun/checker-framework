package org.checkerframework.framework.util.typeinference;

import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.GeneralAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.TypeHierarchy;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.framework.util.PluginUtil;
import org.checkerframework.framework.util.typeinference.constraint.A2F;
import org.checkerframework.framework.util.typeinference.constraint.A2FReducer;
import org.checkerframework.framework.util.typeinference.constraint.AFConstraint;
import org.checkerframework.framework.util.typeinference.constraint.AFReducer;
import org.checkerframework.framework.util.typeinference.constraint.F2A;
import org.checkerframework.framework.util.typeinference.constraint.F2AReducer;
import org.checkerframework.framework.util.typeinference.constraint.FIsA;
import org.checkerframework.framework.util.typeinference.constraint.FIsAReducer;
import org.checkerframework.framework.util.typeinference.constraint.TSubU;
import org.checkerframework.framework.util.typeinference.constraint.TSuperU;
import org.checkerframework.framework.util.typeinference.constraint.TUConstraint;
import org.checkerframework.framework.util.typeinference.solver.ConstraintMap;
import org.checkerframework.framework.util.typeinference.solver.ConstraintMapBuilder;
import org.checkerframework.framework.util.typeinference.solver.EqualitiesSolver;
import org.checkerframework.framework.util.typeinference.solver.InferenceResult;
import org.checkerframework.framework.util.typeinference.solver.InferredValue;
import org.checkerframework.framework.util.typeinference.solver.InferredValue.InferredType;
import org.checkerframework.framework.util.typeinference.solver.SubtypesSolver;
import org.checkerframework.framework.util.typeinference.solver.SupertypesSolver;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;

import com.sun.source.tree.ExpressionTree;
import com.sun.tools.javac.code.Symbol.MethodSymbol;

/**
 * An implementation of TypeArgumentInference that mostly follows the process outlined in JLS7
 * See <a href="http://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.12.2.7">JLS &sect;5.12.2.7</a>
 *
 * Note, there are some deviations JLS 7 for the following cases:
 *     a.) Places where the JLS is vague.  For these cases, first the OpenJDK implementation was consulted
 *     and then we favored the behavior we desire rather than the implied behavior of the JLS or JDK implementation.
 *
 *     b.) The fact that any given type variable type may or may not have annotations for multiple hierarchies means
 *     that constraints are more complicated than their Java equivalents.  Every constraint must identify the
 *     hierarchies to which they apply.  This makes solving the constraint sets more complicated.
 *
 *  TODO: The following limitations need to be fixed, as at the time of this writing we do not have the time
 *        to handle them:
 *      1) The GlbUtil does not correctly handled wildcards/typevars when the glb result should be a wildcard or typevar
 *      2) Interdependent Method Invocations - Currently we do not correctly handle the case where two methods need
 *         to have their arguments inferred and one is the argument to the other.
 *         E.g.
 * <pre>{@code
 *              <T> T get()
 *              <S> void set(S s)
 *              set(get())
 * }</pre>
 *         Presumably, we want to detect these situations and combine the set of constraints with {@code T <: S}.
 */
public class DefaultTypeArgumentInference implements TypeArgumentInference {
    private final EqualitiesSolver equalitiesSolver = new EqualitiesSolver();
    private final SupertypesSolver supertypesSolver = new SupertypesSolver();
    private final SubtypesSolver subtypesSolver = new SubtypesSolver();
    private final ConstraintMapBuilder constraintMapBuilder = new ConstraintMapBuilder();

    @Override
    public Map<TypeVariable, AnnotatedTypeMirror> inferTypeArgs(AnnotatedTypeFactory typeFactory,
                                                                ExpressionTree expressionTree,
                                                                ExecutableElement methodElem,
                                                                AnnotatedExecutableType methodType) {

        //TODO: REMOVE THIS HACK WHEN YOU CAN CALL getTopAnnotations on GeneralAnnotatedTypeFactory
        //TODO: currently this will only affect inferring METHOD type arguments on constructor
        //TODO: invocations for the Nullness type system
        if (typeFactory instanceof GeneralAnnotatedTypeFactory) {
            return new HashMap<>();
        }

        final List<AnnotatedTypeMirror> argTypes = getArgumentTypes(expressionTree, typeFactory);
        final AnnotatedTypeMirror assignedTo = getAssignedTo(expressionTree, typeFactory);

        final Set<TypeVariable> targets = TypeArgInferenceUtil.methodTypeToTargets(methodType);
        final Map<TypeVariable, AnnotatedTypeMirror> inferredArgs =
                infer(typeFactory,  argTypes, assignedTo, methodElem, methodType, targets);

        handleUninferredTypeVariables(typeFactory, methodType, targets, inferredArgs);
        return inferredArgs;
    }

    @Override
    public void adaptMethodType(AnnotatedTypeFactory typeFactory, ExpressionTree invocation, AnnotatedExecutableType methodType) {
        //do nothing
    }
    // TODO: THIS IS A BIG VIOLATION OF Single Responsibility and SHOULD BE FIXED, IT IS SOLELY HERE
    // TODO: AS A TEMPORARY KLUDGE BEFORE A RELEASE/SPARTA ENGAGEMENT
    protected List<AnnotatedTypeMirror> getArgumentTypes(final ExpressionTree expression,
                                                         final AnnotatedTypeFactory typeFactory) {
        final List<? extends ExpressionTree> argTrees = TypeArgInferenceUtil.expressionToArgTrees(expression);
        return TypeArgInferenceUtil.treesToTypes(argTrees, typeFactory);
    }


    protected AnnotatedTypeMirror getAssignedTo(ExpressionTree expression, AnnotatedTypeFactory typeFactory ) {
        return TypeArgInferenceUtil.assignedTo(typeFactory, typeFactory.getPath(expression));
    }

    /**
     * This algorithm works as follows:
     * 1.  Build Argument Constraints - create a set of constraints using the arguments to the type parameter declarations,
     * the formal parameters, and the arguments to the method call
     * 2.  Solve Argument Constraints - Create two solutions from the arguments.
     *       a. Equality Arg Solution: Solution inferred from arguments used in an invariant position
     *       (i.e. from equality constraints)
     *       b. Supertypes Arg Solution: Solution inferred from constraints in which the parameter is a supertype of
     *       argument types. These are kept separate and merged later.
     *
     * Note: If there is NO assignment context we just combine the results from 2.a and 2.b, giving preference
     * to those in 2.a, and return the result.
     *
     * 3.  Build and Solve Initial Assignment Constraints - Create a set of constraints from the assignment context WITHOUT
     *       substituting either solution from step 2.
     *
     * 4.  Combine the solutions from steps 2.b and 3.  This handles cases like the following:
     *
     *        <T> List<T> method(T t1) {}
     *        List<@Nullable String> nl = method("");
     *
     *  If we use just the arguments to infer T we will infer @NonNull String (since the lub of all arguments would
     *  be @NonNull String).  However, this would cause the assignment to fail.
     *  Instead, since @NonNull String <: @Nullable String, we can safely infer T to be @Nullable String and
     *  both the argument types and the assignment types are compatible.
     *  In step 4, we combine the results of Step 2.b (which came from lubbing argument and argument component types)
     *  with the solution from equality constraints via the assignment context.
     *
     *  Note, we always give preference to the results inferred from method arguments if there is a conflict between
     *  the steps 2 and 4.  For example:
     *
     *        <T> List<T> method(T t1) {}
     *        List<@NonNull String> nl = method(null);
     *
     *  In the above example, the null argument requires that T must be @Nullable String.  But the assignment
     *  context requires that the T must be @NonNull String.  But, in this case if we use @NonNull String
     *  the argument "null" is invalid.  In this case, we use @Nullable String and report an
     *  assignment.type.incompatible because we ALWAYS favor the arguments over the assignment context.
     *
     * 5. Combine the result from 2.a and step 4, if there is a conflict use the result from step 2.a
     *
     * Suppose we have the following:
     *       <T> void method(List<@NonNull T> t, @Initialized Tt) { ... }
     *       List<@FBCBottom String> lBottom = ...;
     *       method( lbBottom, "nonNullString" );
     *
     * From the first argument we can infer that T must be exactly @FBCBottom String but we cannot infer anything
     * for the Nullness hierarchy.  For the second argument we can infer that T is at most @NonNull String but
     * we can infer nothing in the initialization hierarchy.  In this step we combine these two results, always
     * favoring the equality constraints if there is a conflict.  For the above example we would infer
     * the following:
     *     T -> @FBCBottom @NonNull String
     *
     * Another case covered in this step is:
     *
     *        <T> List<T> method(List<T> t1) {}
     *        List<@NonNull String> nonNullList = new ArrayList<>();
     *        List<@Nullable String> nl = method(nonNullList);
     *
     *  The above assignment should fail because T is forced to be both @NonNull and @Nullable.  In cases like these,
     *  we use @NonNull String becasue we always favor constraints from the arguments over the assignment context.
     *
     * 6. Infer from Assignment Context
     * Finally, the JLS states that we should substitute the types we have inferred up until this point back
     * into the original argument constraints.  We should then combine the constraints we get from the
     * assignment context and solve using the greatest lower bounds of all of the constraints of the form:
     * F :> U  (these are referred to as "subtypes" in the ConstraintMap.TargetConstraints).
     *
     * 7. Merge the result from steps 5 and 6 giving preference to 5 (the argument constraints).
     * Return the result.
     */
    private Map<TypeVariable, AnnotatedTypeMirror> infer(final AnnotatedTypeFactory typeFactory,
                                                         final List<AnnotatedTypeMirror> argumentTypes,
                                                         final AnnotatedTypeMirror assignedTo,
                                                         final ExecutableElement methodElem,
                                                         final AnnotatedExecutableType methodType,
                                                         final Set<TypeVariable> targets) {

        //1.  Step 1 - Build up argument constraints
        //The AFConstraints for arguments are used also in the
        Set<AFConstraint> afArgumentConstraints = createArgumentAFConstraints(typeFactory, argumentTypes, methodType, targets);

        //2. Step 2 - Solve the constraints.
        Pair<InferenceResult, InferenceResult> argInference = inferFromArguments(typeFactory, afArgumentConstraints, targets);

        final InferenceResult fromArgEqualities = argInference.first;  //result 2.a
        final InferenceResult fromArgSubandSupers = argInference.second; //result 2.b

        clampToLowerBound(fromArgSubandSupers, methodType.getTypeVariables(), typeFactory);

        //if this method invocation's has a return type and it is assigned/pseudo-assigned to
        //a variable, assignedTo is the type of that variable
        if (assignedTo == null) {
            fromArgEqualities.mergeSubordinate(fromArgSubandSupers);
            return fromArgEqualities.toAtmMap();
        } //else

        final AnnotatedTypeMirror declaredReturnType = methodType.getReturnType();
        final AnnotatedTypeMirror boxedReturnType;
        if (declaredReturnType == null) {
            boxedReturnType = null;
        } else if (declaredReturnType.getKind().isPrimitive()) {
            boxedReturnType = typeFactory.getBoxedType((AnnotatedPrimitiveType) declaredReturnType);
        } else {
            boxedReturnType = declaredReturnType;
        }

        final InferenceResult fromArguments = fromArgEqualities;
        if (!((MethodSymbol)methodElem).isConstructor()) {
            // Step 3 - Infer a solution from the equality constraints in the assignment context
            InferenceResult fromAssignmentEqualities =
                    inferFromAssignmentEqualities(assignedTo, boxedReturnType, targets, typeFactory);

            // Step 4 - Combine the results from 2.b and step 3
            InferenceResult combinedSupertypesAndAssignment =
                    combineSupertypeAndAssignmentResults(targets, typeFactory, fromAssignmentEqualities, fromArgSubandSupers);

            // Step 5 - Combine the result from 2.a and step 4, if there is a conflict use the result from step 2.a
            fromArgEqualities.mergeSubordinate(combinedSupertypesAndAssignment);

            //if we don't have a result for all type arguments
            //Step 6 - Infer the type arguments from the greatest-lower-bounds of all "subtype" constraints
            if (!fromArguments.isComplete(targets)) {
                InferenceResult fromAssignment = inferFromAssignment(assignedTo, boxedReturnType, methodType, afArgumentConstraints,
                        fromArguments, targets, typeFactory);

                //Step 7 - Merge the argument and the assignment constraints
                fromArguments.mergeSubordinate(fromAssignment);
            }

        } else {

            fromArguments.mergeSubordinate(fromArgSubandSupers);
        }

        return fromArguments.toAtmMap();

    }

    /**
     * If we have inferred a type argument from the supertype constraints and this type argument is BELOW the
     * lower bound, make it AT the lower bound
     *
     * e.g.
     * {@code
     *   <@Initialized T extends @Initialized Object> void id(T t) { return t; }
     *   id(null);
     *
     *   //The invocation of id will result in a type argument with primary annotations of @FBCBottom @Nullable
     *   //but this is below the lower bound of T in the initialization hierarchy so instead replace
     *   //@FBCBottom with @Initialized
     *
     *   //This should happen ONLY with supertype constraints because raising the primary annotation would still
     *   //be valid for these constraints (since we just LUB the arguments involved) but would violate any
     *   //equality constraints
     * }
     *
     * TODO: NOTE WE ONLY DO THIS FOR InferredType results for now but we should probably include targest as well
     *
     * @param fromArgSupertypes types inferred from LUBbing types from the arguments to the formal parameters
     * @param targetDeclarations the declared types of the type parameters whose arguments are being inferred
     */
    private void clampToLowerBound(InferenceResult fromArgSupertypes, List<AnnotatedTypeVariable> targetDeclarations,
                                   AnnotatedTypeFactory typeFactory) {
        final Types types = typeFactory.getProcessingEnv().getTypeUtils();
        final QualifierHierarchy qualifierHierarchy = typeFactory.getQualifierHierarchy();
        final Set<? extends AnnotationMirror> tops = qualifierHierarchy.getTopAnnotations();

        for (AnnotatedTypeVariable targetDecl : targetDeclarations ) {
            InferredValue inferred = fromArgSupertypes.get(targetDecl.getUnderlyingType());
            if (inferred != null && inferred instanceof InferredType) {
                final AnnotatedTypeMirror lowerBoundAsArgument =
                        AnnotatedTypes.asSuper(types, typeFactory, targetDecl.getLowerBound(), ((InferredType) inferred).type);


                for (AnnotationMirror top : tops) {
                    final AnnotationMirror lowerBoundAnno = lowerBoundAsArgument.getEffectiveAnnotationInHierarchy(top);
                    final AnnotationMirror argAnno = ((InferredType) inferred).type.getEffectiveAnnotationInHierarchy(top);
                    if (qualifierHierarchy.isSubtype(argAnno, lowerBoundAnno)) {
                        ((InferredType) inferred).type.replaceAnnotation(lowerBoundAnno);
                    }
                }
            }
        }
    }

    /**
     * Step 1:
     * Create a constraint {@code Ai << Fi} for each Argument(Ai) to formal parameter(Fi).  Remove any constraint that
     * does not involve a type parameter to be inferred.  Reduce the remaining constraints so that Fi = Tj
     * where Tj is a type parameter with an argument to be inferred.
     * Return the resulting constraint set.
     */
    protected Set<AFConstraint> createArgumentAFConstraints(final AnnotatedTypeFactory typeFactory,
                                                            final List<AnnotatedTypeMirror> argTypes,
                                                            final AnnotatedExecutableType methodType,
                                                            final Set<TypeVariable> targets) {
        final List<AnnotatedTypeMirror> paramTypes = AnnotatedTypes.expandVarArgsFromTypes(methodType, argTypes);

        if (argTypes.size() != paramTypes.size()) {
            ErrorReporter.errorAbort(
                    "Mismatch between formal parameter count and argument count!\n"
                            + "paramTypes=" + PluginUtil.join(",", paramTypes) + "\n"
                            + "argTypes=" + PluginUtil.join(",", argTypes)
            );
        }

        final int numberOfParams = paramTypes.size();
        final LinkedList<AFConstraint> afConstraints = new LinkedList<>();
        for (int i = 0; i < numberOfParams; i++) {
            afConstraints.add(new A2F(argTypes.get(i), paramTypes.get(i)));
        }

        final Set<AFConstraint> reducedConstraints = new LinkedHashSet<>();

        reduceAfConstraints(typeFactory, reducedConstraints, afConstraints, targets);
        return reducedConstraints;
    }

    /**
     * Step 2.
     * Infer type arguments from the equality (TisU) and the supertype (TSuperU) constraints of
     * the methods arguments.
     */
    private Pair<InferenceResult,InferenceResult> inferFromArguments(final AnnotatedTypeFactory typeFactory,
                                                                     final Set<AFConstraint> afArgumentConstraints,
                                                                     final Set<TypeVariable> targets) {
        Set<TUConstraint> tuArgConstraints = afToTuConstraints(afArgumentConstraints, targets);
        addConstraintsBetweenTargets(tuArgConstraints, targets, false, typeFactory);

        ConstraintMap argConstraints = constraintMapBuilder.build(targets, tuArgConstraints, typeFactory);

        InferenceResult inferredFromArgEqualities = equalitiesSolver.solveEqualities(targets, argConstraints, typeFactory);

        Set<TypeVariable> remainingTargets =  inferredFromArgEqualities.getRemainingTargets(targets, true);
        InferenceResult inferredFromLubs = supertypesSolver.solveFromSupertypes(remainingTargets, argConstraints, typeFactory);

        //TODO: solveFromSubtypes and then fromSubtypes (in infer) are misnamed if we use this method in this context
        //TODO: find a clearer name
        InferenceResult inferredFromGlbs = subtypesSolver.solveFromSubtypes(remainingTargets, argConstraints, typeFactory);
        inferredFromLubs.mergeSubordinate(inferredFromGlbs);

        return Pair.of(inferredFromArgEqualities, inferredFromLubs);
    }

    /**
     * Step 3.
     * Infer type arguments from the equality constraints of the assignment context.
     */
    private InferenceResult inferFromAssignmentEqualities(final AnnotatedTypeMirror assignedTo,
                                                          final AnnotatedTypeMirror boxedReturnType,
                                                          final Set<TypeVariable> targets,
                                                          final AnnotatedTypeFactory typeFactory) {
        Set<FIsA> afInitialAssignmentConstraints =
                createInitialAssignmentConstraints(assignedTo, boxedReturnType, typeFactory, targets);


        Set<TUConstraint> tuInitialAssignmentConstraints = afToTuConstraints(afInitialAssignmentConstraints, targets);
        ConstraintMap initialAssignmentConstraints = constraintMapBuilder.build(targets, tuInitialAssignmentConstraints, typeFactory);
        return equalitiesSolver.solveEqualities(targets, initialAssignmentConstraints, typeFactory);
    }

    /**
     * Create a set of constraints between return type and any type to which it is assigned.  Reduce these
     * set of constraints and remove any that is not an equality (FIsA) constraint.
     */
    protected Set<FIsA> createInitialAssignmentConstraints(final AnnotatedTypeMirror assignedTo,
                                                           final AnnotatedTypeMirror boxedReturnType,
                                                           final AnnotatedTypeFactory typeFactory,
                                                           final Set<TypeVariable> targets) {
        final Set<FIsA> result = new LinkedHashSet<>();

        if (assignedTo != null) {
            final Set<AFConstraint> reducedConstraints = new LinkedHashSet<>();

            final Queue<AFConstraint> constraints = new LinkedList<>();
            constraints.add(new F2A(boxedReturnType, assignedTo));

            reduceAfConstraints(typeFactory, reducedConstraints, constraints, targets);

            for (final AFConstraint reducedConstraint : reducedConstraints) {
                if (reducedConstraint instanceof FIsA) {
                    result.add((FIsA) reducedConstraint);
                }
            }
        }

        return result;
    }

    /**
     * The first half of Step 6.
     * This method creates constraints:
     *     a) between the bounds of types that are already inferred and their inferred arguments
     *     b) between the assignment context and the return type of the method (with the previously inferred
     *     arguments substituted into these constraints)
     */
    public ConstraintMap createAssignmentConstraints(final AnnotatedTypeMirror assignedTo,
                                                     final AnnotatedTypeMirror boxedReturnType,
                                                     final AnnotatedExecutableType methodType,
                                                     final Set<AFConstraint> afArgumentConstraints,
                                                     final Map<TypeVariable, AnnotatedTypeMirror> inferredArgs,
                                                     final Set<TypeVariable> targets,
                                                     final AnnotatedTypeFactory typeFactory) {

        final LinkedList<AFConstraint> assignmentAfs = new LinkedList<>();
        for (AnnotatedTypeVariable typeParam : methodType.getTypeVariables()) {
            final TypeVariable target = typeParam.getUnderlyingType();
            final AnnotatedTypeMirror inferredType = inferredArgs.get(target);
            //for all inferred types Ti:  Ti >> Bi where Bi is upper bound and Ti << Li where Li is the lower bound
            //for all uninferred types Tu: Tu >> Bi and Lu >> Tu
            if (inferredType != null) {
                assignmentAfs.add(new A2F(inferredType, typeParam.getUpperBound()));
                assignmentAfs.add(new F2A(typeParam.getLowerBound(), inferredType));
            } else {
                assignmentAfs.add(new F2A(typeParam, typeParam.getUpperBound()));
                assignmentAfs.add(new A2F(typeParam.getLowerBound(), typeParam));
            }
        }

        for (AFConstraint argConstraint : afArgumentConstraints) {
            if (argConstraint instanceof F2A) {
                assignmentAfs.add(argConstraint);
            }
        }

        LinkedList<AFConstraint> substitutedAssignmentConstraints = new LinkedList<>();
        for (AFConstraint afConstraint : assignmentAfs) {
            substitutedAssignmentConstraints.add(afConstraint.substitute(inferredArgs));
        }

        final AnnotatedTypeMirror substitutedReturnType = TypeArgInferenceUtil.substitute(inferredArgs, boxedReturnType);
        substitutedAssignmentConstraints.add(new F2A(substitutedReturnType, assignedTo));

        final Set<AFConstraint> reducedConstraints = new LinkedHashSet<>();
        reduceAfConstraints(typeFactory, reducedConstraints, substitutedAssignmentConstraints, targets);
        final Set<TUConstraint> tuAssignmentConstraints = afToTuConstraints(reducedConstraints, targets);
        addConstraintsBetweenTargets(tuAssignmentConstraints, targets, true, typeFactory);
        return constraintMapBuilder.build(targets, tuAssignmentConstraints, typeFactory);
    }

    /**
     * The Second half of step 6.
     * Use the assignment context to infer a result.
     */
    private InferenceResult inferFromAssignment(final AnnotatedTypeMirror assignedTo,
                                                final AnnotatedTypeMirror boxedReturnType,
                                                final AnnotatedExecutableType methodType,
                                                final Set<AFConstraint> afArgumentConstraints,
                                                final InferenceResult inferredArgs,
                                                final Set<TypeVariable> targets,
                                                final AnnotatedTypeFactory typeFactory) {
        ConstraintMap assignmentConstraints =
                createAssignmentConstraints(assignedTo, boxedReturnType, methodType, afArgumentConstraints,
                        inferredArgs.toAtmMap(), targets, typeFactory);

        InferenceResult equalitiesResult = equalitiesSolver.solveEqualities(targets, assignmentConstraints, typeFactory);

        Set<TypeVariable> remainingTargets = equalitiesResult.getRemainingTargets(targets, true);
        InferenceResult subtypesResult = subtypesSolver.solveFromSubtypes(remainingTargets, assignmentConstraints, typeFactory);

        equalitiesResult.mergeSubordinate(subtypesResult);
        return equalitiesResult;
    }

    /**
     * Step 4. Combine the results from using the Supertype constraints the Equality constraints from the
     * assignment context.
     */
    private InferenceResult combineSupertypeAndAssignmentResults(Set<TypeVariable> targets, AnnotatedTypeFactory typeFactory,
                                                                 InferenceResult equalityResult, InferenceResult supertypeResult) {
        final TypeHierarchy typeHierarchy = typeFactory.getTypeHierarchy();

        final InferenceResult result = new InferenceResult();
        for (final TypeVariable target : targets) {
            final InferredValue equalityInferred = equalityResult.get(target);
            final InferredValue supertypeInferred = supertypeResult.get(target);

            final InferredValue outputValue;
            if (equalityInferred != null && equalityInferred instanceof InferredType) {

                if (supertypeInferred != null && supertypeInferred instanceof InferredType) {
                    if (typeHierarchy.isSubtype(((InferredType) supertypeInferred).type, ((InferredType) equalityInferred).type)) {
                        outputValue = equalityInferred;
                    } else {
                        outputValue = supertypeInferred;
                    }

                } else {
                    outputValue = equalityInferred;
                }
            } else {
                if (supertypeInferred != null) {
                    outputValue = supertypeInferred;
                } else {
                    outputValue = null;
                }
            }

            if (outputValue != null) {
                result.put(target, outputValue);
            }
        }

        return result;
    }

    /**
     * For any types we have not inferred, use a wildcard with the bounds from the original type parameter.
     * For any types we have inferred to be an AnnotatedNullType, create a wildcard but apply the primary
     * annotations from that AnnotatedNullType.
     */
    private void handleUninferredTypeVariables(AnnotatedTypeFactory typeFactory, AnnotatedExecutableType methodType,
                                               Set<TypeVariable> targets, Map<TypeVariable, AnnotatedTypeMirror> inferredArgs) {

        for (AnnotatedTypeVariable atv : methodType.getTypeVariables()) {
            final TypeVariable typeVar = atv.getUnderlyingType();
            if (targets.contains(typeVar)) {
                final AnnotatedTypeMirror inferredType = inferredArgs.get(typeVar);

                if (inferredType == null || inferredType.getKind() == TypeKind.NULL) {
                    AnnotatedTypeMirror dummy = typeFactory.getUninferredWildcardType(atv);
                    inferredArgs.put(atv.getUnderlyingType(), dummy);

                    if (inferredType != null) { //then the type kind must be TypeKind.NULL
                        dummy.replaceAnnotations(inferredType.getAnnotations());
                    }
                }
            }
        }
    }

    /**
     * Given a set of AFConstraints, remove all constraints that are not relevant to inference and return
     * a set of AFConstraints in which the F is a use of one of the type parameters to infer.
     */
    protected void reduceAfConstraints(final AnnotatedTypeFactory typeFactory,
                                       final Set<AFConstraint> outgoing, final Queue<AFConstraint> toProcess,
                                       final Set<TypeVariable> targets) {

        final Set<AFConstraint> visited = new HashSet<>();

        List<AFReducer> reducers = new ArrayList<>();
        reducers.add(new A2FReducer(typeFactory));
        reducers.add(new F2AReducer(typeFactory));
        reducers.add(new FIsAReducer(typeFactory));

        Set<AFConstraint> newConstraints = new HashSet<>(10);
        while (!toProcess.isEmpty()) {
            newConstraints.clear();
            AFConstraint constraint = toProcess.remove();

            if (!visited.contains(constraint)) {
                if (constraint.isIrreducible(targets)) {
                    outgoing.add(constraint);
                } else {

                    final Iterator<AFReducer> reducerIterator = reducers.iterator();
                    boolean handled = false;
                    while (!handled && reducerIterator.hasNext()) {
                        handled = reducerIterator.next().reduce(constraint, newConstraints);
                    }

                    if (!handled) {
                        ErrorReporter.errorAbort("Unhandled constraint type: " + constraint.toString());
                    }

                    toProcess.addAll(newConstraints);
                }
                visited.add(constraint);
            }
        }
    }


    /**
     * Convert AFConstraints to TUConstraints
     */
    protected Set<TUConstraint> afToTuConstraints(Set<? extends AFConstraint> afConstraints, Set<TypeVariable> targets) {
        final Set<TUConstraint> outgoing = new LinkedHashSet<>();
        for (final AFConstraint afConstraint : afConstraints) {
            if (!afConstraint.isIrreducible(targets)) {
                ErrorReporter.errorAbort(
                        "All afConstraints should be irreducible before conversion.\n"
                                + "afConstraints=[ " + PluginUtil.join(", ", afConstraints) + " ]\n"
                                + "targets=[ " + PluginUtil.join(", ", targets) + "]"
                );
            }

            outgoing.add(afConstraint.toTUConstraint());
        }

        return outgoing;
    }

    /**
     * Declarations of the form:
     * {@code <A, B extends A>} implies a TUConstraint of {@code B <: A}.  Add these to the constraint list.
     */
    public void addConstraintsBetweenTargets(Set<TUConstraint> constraints, Set<TypeVariable> targets,
                                             boolean asSubtype, AnnotatedTypeFactory typeFactory) {
        final Types types = typeFactory.getProcessingEnv().getTypeUtils();
        final List<TypeVariable> targetList = new LinkedList<>(targets);

        final Map<TypeVariable, AnnotatedTypeVariable> paramDeclarations = new HashMap<>();

        for (int i = 0; i < targetList.size(); i++) {
            final TypeVariable earlierTarget = targetList.get(i);

            for (int j = i + 1; j < targetList.size(); j++) {
                final TypeVariable laterTarget = targetList.get(j);
                if (types.isSameType(earlierTarget.getUpperBound(), laterTarget)) {
                    final AnnotatedTypeVariable headDecl = addOrGetDeclarations(earlierTarget, typeFactory, paramDeclarations);
                    final AnnotatedTypeVariable nextDecl = addOrGetDeclarations(laterTarget, typeFactory, paramDeclarations);

                    if (asSubtype) {
                        constraints.add(new TSubU(headDecl, nextDecl));

                    } else {
                        constraints.add(new TSuperU(nextDecl, headDecl));

                    }
                } else if (types.isSameType(laterTarget.getUpperBound(), earlierTarget)) {
                    final AnnotatedTypeVariable headDecl = addOrGetDeclarations(earlierTarget, typeFactory, paramDeclarations);
                    final AnnotatedTypeVariable nextDecl = addOrGetDeclarations(laterTarget, typeFactory, paramDeclarations);

                    if (asSubtype) {
                        constraints.add(new TSubU(nextDecl, headDecl));

                    } else {
                        constraints.add(new TSuperU(headDecl, nextDecl));

                    }

                }
            }
        }
    }

    public AnnotatedTypeVariable addOrGetDeclarations(TypeVariable target, AnnotatedTypeFactory typeFactory,
                                                      Map<TypeVariable, AnnotatedTypeVariable> declarations) {
        AnnotatedTypeVariable atv = declarations.get(target);
        if (atv == null) {
            atv = (AnnotatedTypeVariable) typeFactory.getAnnotatedType(target.asElement());
            declarations.put(target, atv);
        }

        return atv;
    }
}
