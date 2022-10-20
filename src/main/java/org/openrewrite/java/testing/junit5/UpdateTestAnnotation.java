/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5;

import org.intellij.lang.annotations.Language;
import org.openrewrite.Applicability;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.*;
import org.openrewrite.java.search.FindImports;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markup;

import java.time.Duration;
import java.util.Comparator;

public class UpdateTestAnnotation extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate JUnit 4 `@Test` annotations to JUnit5";
    }

    @Override
    public String getDescription() {
        return "Update usages of JUnit 4's `@org.junit.Test` annotation to JUnit5's `org.junit.jupiter.api.Test` annotation.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {
        return Applicability.or(
                new UsesType<>("org.junit.Test"),
                new FindImports("org.junit.Test").getVisitor()
        );
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new UpdateTestAnnotationVisitor();
    }

    private static class UpdateTestAnnotationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private static final AnnotationMatcher JUNIT4_TEST = new AnnotationMatcher("@org.junit.Test");

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext executionContext) {
            J.CompilationUnit c = super.visitCompilationUnit(cu, executionContext);
            maybeRemoveImport("org.junit.Test");
            doAfterVisit(new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                    J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                    // take one more pass over the imports now that we've had a chance to markup all
                    // uses of @Test through the rest of the source file
                    c = c.withImports(ListUtils.map(c.getImports(), anImport -> (J.Import) visit(anImport, ctx)));
                    return c;
                }

                @Override
                public J.Import visitImport(J.Import anImport, ExecutionContext executionContext) {
                    if (anImport.getTypeName().equals("org.junit.Test")) {
                        throw new IllegalStateException("This import should have been removed by this recipe.");
                    }
                    return anImport;
                }

                @Override
                public JavaType visitType(@Nullable JavaType javaType, ExecutionContext executionContext) {
                    if (TypeUtils.isOfClassType(javaType, "org.junit.Test")) {
                        getCursor().dropParentUntil(J.class::isInstance).putMessage("danglingTestRef", true);
                    }
                    return javaType;
                }

                @Override
                public J postVisit(J tree, ExecutionContext executionContext) {
                    if (getCursor().getMessage("danglingTreeRef", false)) {
                        return Markup.warn(tree, "This still has a type of `org.junit.Test`", null);
                    }
                    return tree;
                }
            });
            return c;
        }

        @Override
        protected JavadocVisitor<ExecutionContext> getJavadocVisitor() {
            return new JavadocVisitor<ExecutionContext>(this) {
                @Override
                public Javadoc visitReference(Javadoc.Reference reference, ExecutionContext ctx) {
                    if (reference.getTree() instanceof TypeTree &&
                        TypeUtils.isOfClassType(((TypeTree) reference.getTree()).getType(), "org.junit.Test")) {
                        getCursor().getParentOrThrow().putMessageOnFirstEnclosing(Javadoc.class, "testRef", true);
                    }
                    return reference;
                }

                @Override
                public Javadoc postVisit(Javadoc tree, ExecutionContext executionContext) {
                    if (getCursor().getMessage("testRef", false)) {
                        return null;
                    }
                    return super.postVisit(tree, executionContext);
                }
            };
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            ChangeTestAnnotation cta = new ChangeTestAnnotation();
            J.MethodDeclaration m = (J.MethodDeclaration) cta.visitNonNull(method, ctx, getCursor().getParentOrThrow());
            if (m != method) {
                if (Boolean.FALSE.equals(TypeUtils.isOverride(m.getMethodType()))) {
                    m = (J.MethodDeclaration) new ChangeMethodAccessLevelVisitor<ExecutionContext>(new MethodMatcher(m), null)
                            .visitNonNull(m, ctx, getCursor().getParentOrThrow());
                }
                if (cta.expectedException != null) {
                    m = m.withTemplate(JavaTemplate.builder(this::getCursor, "Object o = () -> #{}").build(),
                            m.getCoordinates().replaceBody(),
                            m.getBody());

                    assert m.getBody() != null;
                    J.Lambda lambda = (J.Lambda) ((J.VariableDeclarations) m.getBody().getStatements().get(0))
                            .getVariables().get(0).getInitializer();

                    assert lambda != null;
                    lambda = lambda.withType(JavaType.ShallowClass.build("org.junit.jupiter.api.function.Executable"));

                    @Language("java")
                    String[] assertionShims = {
                            "package org.junit.jupiter.api.function;" +
                            "public interface Executable {void execute() throws Throwable;}",
                            "package org.junit.jupiter.api;" +
                            "import org.junit.jupiter.api.function.Executable;" +
                            "public class Assertions {" +
                            "   public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {return null;}" +
                            "   public static void assertDoesNotThrow(Executable executable) {}" +
                            "}"
                    };

                    if (cta.expectedException instanceof J.FieldAccess
                        && TypeUtils.isAssignableTo("org.junit.Test$None", ((J.FieldAccess) cta.expectedException).getTarget().getType())) {
                        m = m.withTemplate(JavaTemplate.builder(this::getCursor, "assertDoesNotThrow(#{any(org.junit.jupiter.api.function.Executable)});")
                                        .javaParser(() -> JavaParser.fromJavaVersion().dependsOn(assertionShims).build())
                                        .staticImports("org.junit.jupiter.api.Assertions.assertDoesNotThrow")
                                        .build(),
                                m.getCoordinates().replaceBody(), lambda);
                        maybeAddImport("org.junit.jupiter.api.Assertions", "assertDoesNotThrow");
                    } else {
                        m = m.withTemplate(JavaTemplate.builder(this::getCursor, "assertThrows(#{any(java.lang.Class)}, #{any(org.junit.jupiter.api.function.Executable)});")
                                        .javaParser(() -> JavaParser.fromJavaVersion().dependsOn(assertionShims).build())
                                        .staticImports("org.junit.jupiter.api.Assertions.assertThrows")
                                        .build(),
                                m.getCoordinates().replaceBody(), cta.expectedException, lambda);
                        maybeAddImport("org.junit.jupiter.api.Assertions", "assertThrows");
                    }
                }
                if (cta.timeout != null) {
                    m = m.withTemplate(
                            JavaTemplate.builder(this::getCursor, "@Timeout(#{any(long)})")
                                    .javaParser(() -> JavaParser.fromJavaVersion()
                                            .dependsOn(new String[]{
                                                    "package org.junit.jupiter.api;" +
                                                    "import java.util.concurrent.TimeUnit;" +
                                                    "public @interface Timeout {" +
                                                    "    long value();" +
                                                    "    TimeUnit unit() default TimeUnit.SECONDS;" +
                                                    "}"
                                            })
                                            .build())
                                    .imports("org.junit.jupiter.api.Timeout")
                                    .build(),
                            m.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)),
                            cta.timeout);
                    maybeAddImport("org.junit.jupiter.api.Timeout");
                }
                maybeAddImport("org.junit.jupiter.api.Test");
            }

            return super.visitMethodDeclaration(m, ctx);
        }

        private static class ChangeTestAnnotation extends JavaIsoVisitor<ExecutionContext> {
            @Nullable
            Expression expectedException;

            @Nullable
            Expression timeout;

            boolean found;

            @Override
            public J.Annotation visitAnnotation(J.Annotation a, ExecutionContext context) {
                if (!found && JUNIT4_TEST.matches(a)) {
                    // While unlikely, it's possible that a method has an inner class/lambda/etc. with methods that have test annotations
                    // Avoid considering any but the first test annotation found
                    found = true;
                    if (a.getArguments() != null) {
                        for (Expression arg : a.getArguments()) {
                            if (!(arg instanceof J.Assignment)) {
                                continue;
                            }
                            J.Assignment assign = (J.Assignment) arg;
                            String assignParamName = ((J.Identifier) assign.getVariable()).getSimpleName();
                            Expression e = assign.getAssignment();
                            if ("expected".equals(assignParamName)) {
                                expectedException = e;
                            } else if ("timeout".equals(assignParamName)) {
                                timeout = e;
                            }

                        }
                    }
                    a = a.withArguments(null)
                            .withType(JavaType.ShallowClass.build("org.junit.jupiter.api.Test"));
                }
                return a;
            }
        }
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }
}
