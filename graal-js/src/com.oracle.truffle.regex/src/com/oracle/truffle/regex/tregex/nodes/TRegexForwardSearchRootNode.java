/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.regex.CompiledRegex;
import com.oracle.truffle.regex.RegexLanguage;
import com.oracle.truffle.regex.RegexObject;
import com.oracle.truffle.regex.RegexProfile;
import com.oracle.truffle.regex.RegexRootNode;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.result.LazyCaptureGroupsResult;
import com.oracle.truffle.regex.result.PreCalculatedResultFactory;
import com.oracle.truffle.regex.result.RegexResult;
import com.oracle.truffle.regex.result.SingleResult;
import com.oracle.truffle.regex.result.SingleResultLazyStart;
import com.oracle.truffle.regex.result.TraceFinderResult;
import com.oracle.truffle.regex.tregex.nodes.input.InputLengthNode;

import java.util.Objects;

public class TRegexForwardSearchRootNode extends RegexRootNode implements CompiledRegex {

    private static final EagerCaptureGroupRegexSearchNode EAGER_SEARCH_BAILED_OUT = new EagerCaptureGroupRegexSearchNode(null);

    private final CallTarget regexCallTarget;
    private final LazyCaptureGroupRegexSearchNode lazySearchNode;
    private EagerCaptureGroupRegexSearchNode eagerSearchNode;

    @Child private RunRegexSearchNode runRegexSearchNode;

    public TRegexForwardSearchRootNode(RegexLanguage language, RegexSource source,
                    PreCalculatedResultFactory[] preCalculatedResults,
                    TRegexDFAExecutorNode forwardExecutor,
                    TRegexDFAExecutorNode backwardExecutor,
                    TRegexDFAExecutorNode captureGroupExecutor) {
        super(language, forwardExecutor.getProperties().getFrameDescriptor(), source);
        lazySearchNode = new LazyCaptureGroupRegexSearchNode(language, source, preCalculatedResults, forwardExecutor, backwardExecutor, captureGroupExecutor);
        runRegexSearchNode = insert(lazySearchNode);
        regexCallTarget = Truffle.getRuntime().createCallTarget(this);
    }

    @Override
    public final RegexResult execute(VirtualFrame frame, RegexObject regex, Object input, int fromIndex) {
        final RegexResult result = runRegexSearchNode.run(frame, regex, input, fromIndex);
        if (CompilerDirectives.inInterpreter() && runRegexSearchNode == lazySearchNode) {
            RegexProfile profile = regex.getRegexProfile();
            if (profile.atEvaluationTripPoint() && profile.shouldUseEagerMatching()) {
                if (eagerSearchNode == null) {
                    TRegexDFAExecutorNode executorNode = Objects.requireNonNull(getLanguage(RegexLanguage.class)).getTRegexEngine().compileEagerDFAExecutor(getSource());
                    if (executorNode == null) {
                        eagerSearchNode = EAGER_SEARCH_BAILED_OUT;
                    } else {
                        eagerSearchNode = new EagerCaptureGroupRegexSearchNode(executorNode);
                    }
                }
                runRegexSearchNode = insert(eagerSearchNode);
            }
            profile.incCalls();
            if (result != RegexResult.NO_MATCH) {
                profile.incMatches();
            }
        }
        return result;
    }

    @Override
    public CallTarget getRegexCallTarget() {
        return regexCallTarget;
    }

    @Override
    public final String getEngineLabel() {
        return "TRegex fwd";
    }

    abstract static class RunRegexSearchNode extends Node {

        @Child InputLengthNode inputLengthNode = InputLengthNode.create();

        abstract RegexResult run(VirtualFrame frame, RegexObject regex, Object input, int fromIndexArg);
    }

    static final class LazyCaptureGroupRegexSearchNode extends RunRegexSearchNode {

        private final RegexSource source;
        private final PreCalculatedResultFactory[] preCalculatedResults;

        @Child private TRegexDFAExecutorNode forwardExecutorNode;
        private final TRegexDFAExecutorNode backwardExecutorNode;
        private final TRegexDFAExecutorNode captureGroupExecutorNode;

        private final CallTarget backwardCallTarget;
        private final CallTarget captureGroupCallTarget;

        LazyCaptureGroupRegexSearchNode(RegexLanguage language,
                        RegexSource source,
                        PreCalculatedResultFactory[] preCalculatedResults,
                        TRegexDFAExecutorNode forwardNode,
                        TRegexDFAExecutorNode backwardNode,
                        TRegexDFAExecutorNode captureGroupExecutor) {
            this.forwardExecutorNode = forwardNode;
            this.source = source;
            this.preCalculatedResults = preCalculatedResults;
            this.backwardExecutorNode = backwardNode;
            backwardCallTarget = Truffle.getRuntime().createCallTarget(
                            new TRegexLazyFindStartRootNode(language, source, forwardNode.getPrefixLength(), backwardNode));
            this.captureGroupExecutorNode = captureGroupExecutor;
            if (captureGroupExecutor == null) {
                captureGroupCallTarget = null;
            } else {
                captureGroupCallTarget = Truffle.getRuntime().createCallTarget(
                                new TRegexLazyCaptureGroupsRootNode(language, source, captureGroupExecutor));
            }
        }

        @Override
        RegexResult run(VirtualFrame frame, RegexObject regex, Object input, int fromIndexArg) {
            if (backwardExecutorNode.isAnchored()) {
                return executeBackwardAnchored(frame, regex, input, fromIndexArg);
            } else {
                return executeForward(frame, regex, input, fromIndexArg);
            }
        }

        private RegexResult executeForward(VirtualFrame frame, RegexObject regex, Object input, int fromIndexArg) {
            forwardExecutorNode.setInput(frame, input);
            forwardExecutorNode.setFromIndex(frame, fromIndexArg);
            forwardExecutorNode.setIndex(frame, fromIndexArg);
            forwardExecutorNode.setMaxIndex(frame, inputLengthNode.execute(input));
            forwardExecutorNode.execute(frame);
            final int end = forwardExecutorNode.getResultInt(frame);
            if (end == TRegexDFAExecutorNode.NO_MATCH) {
                return RegexResult.NO_MATCH;
            }
            if (singlePreCalcResult()) {
                return preCalculatedResults[0].createFromEnd(regex, input, end);
            }
            if (preCalculatedResults == null && captureGroupExecutorNode == null) {
                if (end == fromIndexArg) { // zero-length match
                    return new SingleResult(regex, input, end, end);
                }
                if (forwardExecutorNode.isAnchored() || source.getFlags().isSticky()) {
                    return new SingleResult(regex, input, fromIndexArg, end);
                }
                return new SingleResultLazyStart(regex, input, fromIndexArg, end, backwardCallTarget);
            } else {
                if (preCalculatedResults != null) { // traceFinder
                    return new TraceFinderResult(regex, input, fromIndexArg, end, backwardCallTarget, preCalculatedResults);
                } else {
                    if (forwardExecutorNode.isAnchored() || (source.getFlags().isSticky() && forwardExecutorNode.getPrefixLength() == 0)) {
                        return new LazyCaptureGroupsResult(regex, input, fromIndexArg, end, captureGroupExecutorNode.getNumberOfCaptureGroups(), null, captureGroupCallTarget);
                    }
                    return new LazyCaptureGroupsResult(regex, input, fromIndexArg, end, captureGroupExecutorNode.getNumberOfCaptureGroups(), backwardCallTarget, captureGroupCallTarget);
                }
            }
        }

        private RegexResult executeBackwardAnchored(VirtualFrame frame, RegexObject regex, Object input, int fromIndexArg) {
            final int inputLength = inputLengthNode.execute(input);
            backwardExecutorNode.setInput(frame, input);
            backwardExecutorNode.setFromIndex(frame, 0);
            backwardExecutorNode.setIndex(frame, inputLength - 1);
            backwardExecutorNode.setMaxIndex(frame, Math.max(-1, fromIndexArg - 1 - forwardExecutorNode.getPrefixLength()));
            backwardExecutorNode.execute(frame);
            final int backwardResult = backwardExecutorNode.getResultInt(frame);
            if (backwardResult == TRegexDFAExecutorNode.NO_MATCH) {
                return RegexResult.NO_MATCH;
            }
            if (multiplePreCalcResults()) { // traceFinder
                return preCalculatedResults[backwardResult].createFromEnd(regex, input, inputLength);
            }
            final int start = backwardResult + 1;
            if (singlePreCalcResult()) {
                return preCalculatedResults[0].createFromStart(regex, input, start);
            }
            if (captureGroupExecutorNode != null) {
                return new LazyCaptureGroupsResult(regex, input, start, inputLength, captureGroupExecutorNode.getNumberOfCaptureGroups(), null, captureGroupCallTarget);
            }
            return new SingleResult(regex, input, start, inputLength);
        }

        private boolean singlePreCalcResult() {
            return preCalculatedResults != null && preCalculatedResults.length == 1;
        }

        private boolean multiplePreCalcResults() {
            return preCalculatedResults != null && preCalculatedResults.length > 1;
        }
    }

    static final class EagerCaptureGroupRegexSearchNode extends RunRegexSearchNode {

        @Child private TRegexDFAExecutorNode executorNode;

        EagerCaptureGroupRegexSearchNode(TRegexDFAExecutorNode executorNode) {
            this.executorNode = executorNode;
        }

        @Override
        RegexResult run(VirtualFrame frame, RegexObject regex, Object input, int fromIndexArg) {
            executorNode.setInput(frame, input);
            executorNode.setFromIndex(frame, fromIndexArg);
            executorNode.setIndex(frame, fromIndexArg);
            executorNode.setMaxIndex(frame, inputLengthNode.execute(input));
            executorNode.execute(frame);
            final int[] resultArray = executorNode.getResultCaptureGroups(frame);
            if (resultArray == null) {
                return RegexResult.NO_MATCH;
            }
            return new LazyCaptureGroupsResult(regex, input, resultArray);
        }
    }
}
