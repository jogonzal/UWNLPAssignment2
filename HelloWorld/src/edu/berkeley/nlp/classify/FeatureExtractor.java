package edu.berkeley.nlp.classify;

import edu.berkeley.nlp.util.Counter;

/**
 * Feature extractors process input instances into feature counters.
 *
 * @author Dan Klein
 */
public interface FeatureExtractor<I,O> {
  Counter<O> extractFeatures(I instance);
}
