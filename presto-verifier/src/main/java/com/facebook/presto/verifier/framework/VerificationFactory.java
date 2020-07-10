/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.framework;

import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.verifier.annotation.ForTest;
import com.facebook.presto.verifier.checksum.ChecksumValidator;
import com.facebook.presto.verifier.prestoaction.NodeResourceClient;
import com.facebook.presto.verifier.prestoaction.PrestoAction;
import com.facebook.presto.verifier.prestoaction.PrestoActionFactory;
import com.facebook.presto.verifier.prestoaction.SqlExceptionClassifier;
import com.facebook.presto.verifier.resolver.FailureResolverFactoryContext;
import com.facebook.presto.verifier.resolver.FailureResolverManager;
import com.facebook.presto.verifier.resolver.FailureResolverManagerFactory;
import com.facebook.presto.verifier.rewrite.QueryRewriter;
import com.facebook.presto.verifier.rewrite.QueryRewriterFactory;

import javax.inject.Inject;

import java.util.Optional;

import static com.facebook.presto.verifier.framework.VerifierUtil.PARSING_OPTIONS;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class VerificationFactory
{
    private final SqlParser sqlParser;
    private final PrestoActionFactory prestoActionFactory;
    private final QueryRewriterFactory queryRewriterFactory;
    private final FailureResolverManagerFactory failureResolverManagerFactory;
    private final NodeResourceClient testResourceClient;
    private final ChecksumValidator checksumValidator;
    private final SqlExceptionClassifier exceptionClassifier;
    private final VerifierConfig verifierConfig;
    private final TypeManager typeManager;
    private final DeterminismAnalyzerConfig determinismAnalyzerConfig;

    @Inject
    public VerificationFactory(
            SqlParser sqlParser,
            PrestoActionFactory prestoActionFactory,
            QueryRewriterFactory queryRewriterFactory,
            FailureResolverManagerFactory failureResolverManagerFactory,
            @ForTest NodeResourceClient testResourceClient,
            ChecksumValidator checksumValidator,
            SqlExceptionClassifier exceptionClassifier,
            VerifierConfig verifierConfig,
            TypeManager typeManager,
            DeterminismAnalyzerConfig determinismAnalyzerConfig)
    {
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.prestoActionFactory = requireNonNull(prestoActionFactory, "prestoActionFactory is null");
        this.queryRewriterFactory = requireNonNull(queryRewriterFactory, "queryRewriterFactory is null");
        this.failureResolverManagerFactory = requireNonNull(failureResolverManagerFactory, "failureResolverManagerFactory is null");
        this.testResourceClient = requireNonNull(testResourceClient, "testResourceClient is null");
        this.checksumValidator = requireNonNull(checksumValidator, "checksumValidator is null");
        this.exceptionClassifier = requireNonNull(exceptionClassifier, "exceptionClassifier is null");
        this.verifierConfig = requireNonNull(verifierConfig, "config is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.determinismAnalyzerConfig = requireNonNull(determinismAnalyzerConfig, "determinismAnalyzerConfig is null");
    }

    public Verification get(SourceQuery sourceQuery, Optional<VerificationContext> existingContext)
    {
        QueryType queryType = QueryType.of(sqlParser.createStatement(sourceQuery.getControlQuery(), PARSING_OPTIONS));
        switch (queryType.getCategory()) {
            case DATA_PRODUCING:
                VerificationContext verificationContext = existingContext.map(VerificationContext::createForResubmission).orElseGet(VerificationContext::create);
                PrestoAction prestoAction = prestoActionFactory.create(sourceQuery, verificationContext);
                QueryRewriter queryRewriter = queryRewriterFactory.create(prestoAction);
                DeterminismAnalyzer determinismAnalyzer = new DeterminismAnalyzer(
                        sourceQuery,
                        prestoAction,
                        queryRewriter,
                        checksumValidator,
                        typeManager,
                        determinismAnalyzerConfig);
                FailureResolverManager failureResolverManager = failureResolverManagerFactory.create(new FailureResolverFactoryContext(
                        sqlParser,
                        prestoAction,
                        testResourceClient));
                return new DataVerification(
                        prestoAction,
                        sourceQuery,
                        queryRewriter,
                        determinismAnalyzer,
                        failureResolverManager,
                        exceptionClassifier,
                        verificationContext,
                        verifierConfig,
                        typeManager,
                        checksumValidator);
            default:
                throw new IllegalStateException(format("Unsupported query type: %s", queryType));
        }
    }
}
