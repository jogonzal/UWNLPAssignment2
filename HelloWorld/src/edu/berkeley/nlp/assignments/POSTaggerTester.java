package edu.berkeley.nlp.assignments;

import edu.berkeley.nlp.io.PennTreebankReader;
import edu.berkeley.nlp.ling.Tree;
import edu.berkeley.nlp.ling.Trees;
import edu.berkeley.nlp.util.*;
import jorge.ObtainLastNCharacters;
import jorge.PseudoWordClassifier;

import java.util.*;
import java.util.regex.Pattern;

/**
 * @author Dan Klein
 */
public class POSTaggerTester {

  static final String START_WORD = "<S>";
  static final String STOP_WORD = "</S>";
  static final String START_TAG = "<S>";
  static final String STOP_TAG = "</S>";

  /**
   * Tagged sentences are a bundling of a list of words and a list of their
   * tags.
   */
  static class TaggedSentence {
    List<String> words;
    List<String> tags;

    public int size() {
      return words.size();
    }

    public List<String> getWords() {
      return words;
    }

    public List<String> getTags() {
      return tags;
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int position = 0; position < words.size(); position++) {
        String word = words.get(position);
        String tag = tags.get(position);
        sb.append(word);
        sb.append("_");
        sb.append(tag);
      }
      return sb.toString();
    }

    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof TaggedSentence)) return false;

      final TaggedSentence taggedSentence = (TaggedSentence) o;

      if (tags != null ? !tags.equals(taggedSentence.tags) : taggedSentence.tags != null) return false;
      if (words != null ? !words.equals(taggedSentence.words) : taggedSentence.words != null) return false;

      return true;
    }

    public int hashCode() {
      int result;
      result = (words != null ? words.hashCode() : 0);
      result = 29 * result + (tags != null ? tags.hashCode() : 0);
      return result;
    }

    public TaggedSentence(List<String> words, List<String> tags) {
      this.words = words;
      this.tags = tags;
    }
  }

  /**
   * States are pairs of tags along with a position index, representing the two
   * tags preceding that position.  So, the START state, which can be gotten by
   * State.getStartState() is [START, START, 0].  To build an arbitrary state,
   * for example [DT, NN, 2], use the static factory method
   * State.buildState("DT", "NN", 2).  There isnt' a single final state, since
   * sentences lengths vary, so State.getEndState(i) takes a parameter for the
   * length of the sentence.
   */
  static class State {

    private static transient Interner<State> stateInterner = new Interner<State>(new Interner.CanonicalFactory<State>() {
      public State build(State state) {
        return new State(state);
      }
    });

    private static transient State tempState = new State();

    public static State getStartState() {
      return buildState(START_TAG, START_TAG, 0);
    }

    public static State getStopState(int position) {
      return buildState(STOP_TAG, STOP_TAG, position);
    }

    public static State buildState(String previousPreviousTag, String previousTag, int position) {
      tempState.setState(previousPreviousTag, previousTag, position);
      return stateInterner.intern(tempState);
    }

    public static List<String> toTagList(List<State> states) {
      List<String> tags = new ArrayList<String>();
      if (states.size() > 0) {
        tags.add(states.get(0).getPreviousPreviousTag());
        for (State state : states) {
          tags.add(state.getPreviousTag());
        }
      }
      return tags;
    }

    public int getPosition() {
      return position;
    }

    public String getPreviousTag() {
      return previousTag;
    }

    public String getPreviousPreviousTag() {
      return previousPreviousTag;
    }

    public State getNextState(String tag) {
      return State.buildState(getPreviousTag(), tag, getPosition() + 1);
    }

    public State getPreviousState(String tag) {
      return State.buildState(tag, getPreviousPreviousTag(), getPosition() - 1);
    }

    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof State)) return false;

      final State state = (State) o;

      if (position != state.position) return false;
      if (previousPreviousTag != null ? !previousPreviousTag.equals(state.previousPreviousTag) : state.previousPreviousTag != null) return false;
      if (previousTag != null ? !previousTag.equals(state.previousTag) : state.previousTag != null) return false;

      return true;
    }

    public int hashCode() {
      int result;
      result = position;
      result = 29 * result + (previousTag != null ? previousTag.hashCode() : 0);
      result = 29 * result + (previousPreviousTag != null ? previousPreviousTag.hashCode() : 0);
      return result;
    }

    public String toString() {
      return "[" + getPreviousPreviousTag() + ", " + getPreviousTag() + ", " + getPosition() + "]";
    }

    int position;
    String previousTag;
    String previousPreviousTag;

    private void setState(String previousPreviousTag, String previousTag, int position) {
      this.previousPreviousTag = previousPreviousTag;
      this.previousTag = previousTag;
      this.position = position;
    }

    private State() {
    }

    private State(State state) {
      setState(state.getPreviousPreviousTag(), state.getPreviousTag(), state.getPosition());
    }
  }

  /**
   * A Trellis is a graph with a start state an an end state, along with
   * successor and predecessor functions.
   */
  static class Trellis <S> {
    S startState;
    S endState;
    CounterMap<S, S> forwardTransitions;
    CounterMap<S, S> backwardTransitions;

    /**
     * Get the unique start state for this trellis.
     */
    public S getStartState() {
      return startState;
    }

    public void setStartState(S startState) {
      this.startState = startState;
    }

    /**
     * Get the unique end state for this trellis.
     */
    public S getEndState() {
      return endState;
    }

    public void setStopState(S endState) {
      this.endState = endState;
    }

    /**
     * For a given state, returns a counter over what states can be next in the
     * markov process, along with the cost of that transition.  Caution: a state
     * not in the counter is illegal, and should be considered to have cost
     * Double.NEGATIVE_INFINITY, but Counters score items they don't contain as
     * 0.
     */
    public Counter<S> getForwardTransitions(S state) {
      return forwardTransitions.getCounter(state);

    }


    /**
     * For a given state, returns a counter over what states can precede it in
     * the markov process, along with the cost of that transition.
     */
    public Counter<S> getBackwardTransitions(S state) {
      return backwardTransitions.getCounter(state);
    }

    public void setTransitionCount(S start, S end, double count) {
      forwardTransitions.setCount(start, end, count);
      backwardTransitions.setCount(end, start, count);
    }

    public Trellis() {
      forwardTransitions = new CounterMap<S, S>();
      backwardTransitions = new CounterMap<S, S>();
    }
  }

  /**
   * A TrellisDecoder takes a Trellis and returns a path through that trellis in
   * which the first item is trellis.getStartState(), the last is
   * trellis.getEndState(), and each pair of states is conntected in the
   * trellis.
   */
  static interface TrellisDecoder <S> {
    List<S> getBestPath(Trellis<S> trellis);
  }

  static class VitterbiDecoder <S> implements TrellisDecoder<S> {

    private class TrellisCell{
      double Value;
      ArrayList<S> History;
      public TrellisCell(double value, ArrayList<S> history){
        History = history;
        Value = value;
      }
    }

    private List<Map<S, TrellisCell>> trellisCells;

    public List<S> getBestPath(Trellis<S> trellis) {
      trellisCells = new ArrayList<Map<S, TrellisCell>>();
      List<S> states = new ArrayList<S>();
      S startState = trellis.getStartState();
      Map<S, TrellisCell> counterStart = new HashMap<S, TrellisCell>();
      ArrayList<S> listStart = new ArrayList<S>();
      listStart.add(startState);
      counterStart.put(startState, new TrellisCell(0.0, listStart));
      trellisCells.add(0, counterStart);

      Set<S> currentColumn = new HashSet<S>();
      currentColumn.add(startState);
      int currentColumnIndex = 0;
      while (!currentColumn.contains(trellis.getEndState())) {
        // Instead of using forward transitions, use all states
        Set<S> forwardTransitions = GetAllForwardTransitions(currentColumn, trellis);

        // Get backwards transitions for all of those transitions, and store the argmax of each in that column
        GetBackwardsTransitionAndStore(trellis, forwardTransitions, currentColumnIndex);

        // Advance to the next column
        Set<S> nextColumn = forwardTransitions;
        currentColumn = nextColumn;
        currentColumnIndex++;
      }

      // Get last state history
      for(S s : trellisCells.get(trellisCells.size() - 1).keySet()){
        TrellisCell cell = trellisCells.get(trellisCells.size() - 1).get(s);
        return cell.History;
      }

      throw new RuntimeException("Should never happen");
    }

    private Set<S> GetAllForwardTransitions(Set<S> currentColumn, Trellis<S> trellis) {
      Set<S> forwardTransitions = new HashSet<S>();
      for(S column : currentColumn){
        forwardTransitions.addAll(trellis.getForwardTransitions(column).keySet());
      }
      return forwardTransitions;
    }

    private void GetBackwardsTransitionAndStore(Trellis<S> trellis, Set<S> forwardTransitions, int currentColumnIndex) {

      Map<S, TrellisCell> newTrellisColumn = new HashMap<S, TrellisCell>();
      for(S state : forwardTransitions){
        Counter<S> backwardTransitions = trellis.getBackwardTransitions(state);
        // Pick the mas backward transition
        S maxTransition = null;
        ArrayList<S> maxTransitionHistory = new ArrayList<S>();
        double maxTransitionValue = Double.NEGATIVE_INFINITY;
        Map<S, TrellisCell> previousTransitionLookup = trellisCells.get(currentColumnIndex);
        for(S backwardTransition : backwardTransitions.keySet()){
          double backwardTransitionValue = backwardTransitions.getCount(backwardTransition);

          // Multiply against the most favorable previous index
          double previousValue = previousTransitionLookup.get(backwardTransition).Value;
          ArrayList<S> previousHistory = previousTransitionLookup.get(backwardTransition).History;
          backwardTransitionValue = backwardTransitionValue + previousValue;

          if (backwardTransitionValue == Double.NaN){
            // Fix
            backwardTransitionValue = Double.NEGATIVE_INFINITY;
          }

          if (backwardTransitionValue >= maxTransitionValue){
            // Keep this state
            maxTransitionValue = backwardTransitionValue;
            maxTransition = backwardTransition;
            maxTransitionHistory = (ArrayList<S>)previousHistory.clone();
            maxTransitionHistory.add(state);
          }
        }

        if (maxTransition == null){
          throw new RuntimeException("This should not happen!");
        }

        // Store it
        TrellisCell cell = new TrellisCell(maxTransitionValue, maxTransitionHistory);
        newTrellisColumn.put(state, cell);
      }

      // Add the whole column now
      trellisCells.add(newTrellisColumn);
    }
  }

  static class GreedyDecoder <S> implements TrellisDecoder<S> {
    public List<S> getBestPath(Trellis<S> trellis) {
      List<S> states = new ArrayList<S>();
      S currentState = trellis.getStartState();
      states.add(currentState);
      while (!currentState.equals(trellis.getEndState())) {
        Counter<S> transitions = trellis.getForwardTransitions(currentState);
        S nextState = transitions.argMax();
        states.add(nextState);
        currentState = nextState;
      }
      return states;
    }
  }

  static class POSTagger {

    LocalTrigramScorer localTrigramScorer;
    TrellisDecoder<State> trellisDecoder;

    // chop up the training instances into local contexts and pass them on to the local scorer.
    public void train(List<TaggedSentence> taggedSentences) {
      localTrigramScorer.train(extractLabeledLocalTrigramContexts(taggedSentences));
    }

    // chop up the validation instances into local contexts and pass them on to the local scorer.
    public void validate(List<TaggedSentence> taggedSentences) {
      localTrigramScorer.validate(extractLabeledLocalTrigramContexts(taggedSentences));
    }

    private List<LabeledLocalTrigramContext> extractLabeledLocalTrigramContexts(List<TaggedSentence> taggedSentences) {
      List<LabeledLocalTrigramContext> localTrigramContexts = new ArrayList<LabeledLocalTrigramContext>();
      for (TaggedSentence taggedSentence : taggedSentences) {
        localTrigramContexts.addAll(extractLabeledLocalTrigramContexts(taggedSentence));
      }
      return localTrigramContexts;
    }

    private List<LabeledLocalTrigramContext> extractLabeledLocalTrigramContexts(TaggedSentence taggedSentence) {
      List<LabeledLocalTrigramContext> labeledLocalTrigramContexts = new ArrayList<LabeledLocalTrigramContext>();
      List<String> words = new BoundedList<String>(taggedSentence.getWords(), START_WORD, STOP_WORD);
      List<String> tags = new BoundedList<String>(taggedSentence.getTags(), START_TAG, STOP_TAG);
      for (int position = 0; position <= taggedSentence.size() + 1; position++) {
        labeledLocalTrigramContexts.add(new LabeledLocalTrigramContext(words, position, tags.get(position - 2), tags.get(position - 1), tags.get(position)));
      }
      return labeledLocalTrigramContexts;
    }

    /**
     * Builds a Trellis over a sentence, by starting at the state State, and
     * advancing through all legal extensions of each state already in the
     * trellis.  You should not have to modify this code (or even read it,
     * really).
     */
    private Trellis<State> buildTrellis(List<String> sentence) {
      Trellis<State> trellis = new Trellis<State>();
      trellis.setStartState(State.getStartState());
      State stopState = State.getStopState(sentence.size() + 2);
      trellis.setStopState(stopState);
      Set<State> states = Collections.singleton(State.getStartState());
      for (int position = 0; position <= sentence.size() + 1; position++) {
        Set<State> nextStates = new HashSet<State>();
        for (State state : states) {
          if (state.equals(stopState))
            continue;
          LocalTrigramContext localTrigramContext = new LocalTrigramContext(sentence, position, state.getPreviousPreviousTag(), state.getPreviousTag());
          Counter<String> tagScores = localTrigramScorer.getLogScoreCounter(localTrigramContext);
          for (String tag : tagScores.keySet()) {
            double score = tagScores.getCount(tag);
            State nextState = state.getNextState(tag);
            trellis.setTransitionCount(state, nextState, score);
            nextStates.add(nextState);
          }
        }
//        System.out.println("States: "+nextStates);
        states = nextStates;
      }
      return trellis;
    }

    // to tag a sentence: build its trellis and find a path through that trellis
    public List<String> tag(List<String> sentence) {
      Trellis<State> trellis = buildTrellis(sentence);
      List<State> states = trellisDecoder.getBestPath(trellis);
      List<String> tags = State.toTagList(states);
      tags = stripBoundaryTags(tags);
      return tags;
    }

    /**
     * Scores a tagging for a sentence.  Note that a tag sequence not accepted
     * by the markov process should receive a log score of
     * Double.NEGATIVE_INFINITY.
     */
    public double scoreTagging(TaggedSentence taggedSentence) {
      double logScore = 0.0;
      List<LabeledLocalTrigramContext> labeledLocalTrigramContexts = extractLabeledLocalTrigramContexts(taggedSentence);
      for (LabeledLocalTrigramContext labeledLocalTrigramContext : labeledLocalTrigramContexts) {
        Counter<String> logScoreCounter = localTrigramScorer.getLogScoreCounter(labeledLocalTrigramContext);
        String currentTag = labeledLocalTrigramContext.getCurrentTag();
        if (logScoreCounter.containsKey(currentTag)) {
          logScore += logScoreCounter.getCount(currentTag);
        } else {
          logScore += Double.NEGATIVE_INFINITY;
        }
      }
      return logScore;
    }

    private List<String> stripBoundaryTags(List<String> tags) {
      return tags.subList(2, tags.size() - 2);
    }

    public POSTagger(LocalTrigramScorer localTrigramScorer, TrellisDecoder<State> trellisDecoder) {
      this.localTrigramScorer = localTrigramScorer;
      this.trellisDecoder = trellisDecoder;
    }
  }

  /**
   * A LocalTrigramContext is a position in a sentence, along with the previous
   * two tags -- basically a FeatureVector.
   */
  static class LocalTrigramContext {
    List<String> words;
    int position;
    String previousTag;
    String previousPreviousTag;

    public List<String> getWords() {
      return words;
    }

    public String getCurrentWord() {
      return words.get(position);
    }

    public int getPosition() {
      return position;
    }

    public String getPreviousTag() {
      return previousTag;
    }

    public String getPreviousPreviousTag() {
      return previousPreviousTag;
    }

    public String toString() {
      return "[" + getPreviousPreviousTag() + ", " + getPreviousTag() + ", " + getCurrentWord() + "]";
    }

    public LocalTrigramContext(List<String> words, int position, String previousPreviousTag, String previousTag) {
      this.words = words;
      this.position = position;
      this.previousTag = previousTag;
      this.previousPreviousTag = previousPreviousTag;
    }
  }

  /**
   * A LabeledLocalTrigramContext is a context plus the correct tag for that
   * position -- basically a LabeledFeatureVector
   */
  static class LabeledLocalTrigramContext extends LocalTrigramContext {
    String currentTag;

    public String getCurrentTag() {
      return currentTag;
    }

    public String toString() {
      return "[" + getPreviousPreviousTag() + ", " + getPreviousTag() + ", " + getCurrentWord() + "_" + getCurrentTag() + "]";
    }

    public LabeledLocalTrigramContext(List<String> words, int position, String previousPreviousTag, String previousTag, String currentTag) {
      super(words, position, previousPreviousTag, previousTag);
      this.currentTag = currentTag;
    }
  }

  /**
   * LocalTrigramScorers assign scores to tags occuring in specific
   * LocalTrigramContexts.
   */
  static interface LocalTrigramScorer {
    /**
     * The Counter returned should contain log probabilities, meaning if all
     * values are exponentiated and summed, they should sum to one (if it's a
     * single conditional pobability). For efficiency, the Counter can
     * contain only the tags which occur in the given context
     * with non-zero model probability.
     */
    Counter<String> getLogScoreCounter(LocalTrigramContext localTrigramContext);

    void train(List<LabeledLocalTrigramContext> localTrigramContexts);

    void validate(List<LabeledLocalTrigramContext> localTrigramContexts);
  }

  /**
   * The HMM tag scorer implements HMM with suffix trees to get a probability of a tag showing up
   */
  static class HMMTagScorerWithSuffixTrees implements LocalTrigramScorer {

    boolean restrictTrigrams; // if true, assign log score of Double.NEGATIVE_INFINITY to illegal tag trigrams.

    CounterMap<String, String> wordsToTags = new CounterMap<String, String>();
    Counter<String> tags = new Counter<String>();
    Counter<String> seenTagTrigrams = new Counter<String>();
    Counter<String> seenTagBigrams = new Counter<String>();

    // Using 3 characters for the suffix
    CounterMap<String, String> minorSuffixCounter = new CounterMap<String, String>();
    CounterMap<String, String> superiorSuffixCounter = new CounterMap<String, String>();

    // Setting Teta to empirically - this will still ensure a valid distribution and
    // values from 0.03 - 0.1 seem like good values according to (Thorsten, 2000)
    double teta = 0.05;

    public int getHistorySize() {
      return 2;
    }

    public Counter<String> getLogScoreCounter(LocalTrigramContext localTrigramContext) {
      int position = localTrigramContext.getPosition();
      String word = localTrigramContext.getWords().get(position);

      // TagCounter will directly define the e(...)
      Counter<String> tagCounter;
      if (wordsToTags.keySet().contains(word)) {
        tagCounter = wordsToTags.getCounter(word);
      } else {
        // Need to fill the tagCounter with all the values
        String charEndingMinor = ObtainLastNCharacters.ObtainLastNCharacters(word, 2);
        String charEndingSuperior = ObtainLastNCharacters.ObtainLastNCharacters(word, 3);

        tagCounter = new Counter<String>();
        for(String sampleTag : tags.keySet()){
          double count = (superiorSuffixCounter.getCount(charEndingSuperior, sampleTag) + teta * minorSuffixCounter.getCount(charEndingMinor, sampleTag)) / (1  + teta);
          tagCounter.setCount(sampleTag, count);
        }
      }

      // To calculate q(...), we'll need to evaluate trigram + bigram counts and divide
      Set<String> allowedFollowingTags = allowedFollowingTags(tagCounter.keySet(), localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      Counter<String> logScoreCounter = new Counter<String>();
      String bigramKey = makeBigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      double bigramCount = seenTagBigrams.getCount(bigramKey);
      for (String tag : tagCounter.keySet()) {
        if (!restrictTrigrams || allowedFollowingTags.isEmpty() || allowedFollowingTags.contains(tag)){
          String trigramKey = makeTrigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag(), tag);
          double trigramCount = seenTagTrigrams.getCount(trigramKey);
          double emissionProbability = tagCounter.getCount(tag);
          double logScore = Math.log(trigramCount / bigramCount * emissionProbability);
          logScoreCounter.setCount(tag, logScore);
        }
      }
      return logScoreCounter;
    }

    private Set<String> allowedFollowingTags(Set<String> tags, String previousPreviousTag, String previousTag) {
      Set<String> allowedTags = new HashSet<String>();
      for (String tag : tags) {
        String trigramString = makeTrigramString(previousPreviousTag, previousTag, tag);
        if (seenTagTrigrams.containsKey((trigramString))) {
          allowedTags.add(tag);
        }
      }
      return allowedTags;
    }

    private String makeBigramString(String previousTag, String currentTag) {
      return previousTag + " " + currentTag;
    }

    private String makeTrigramString(String previousPreviousTag, String previousTag, String currentTag) {
      return previousPreviousTag + " " + previousTag + " " + currentTag;
    }

    public void train(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // collect word-tag counts
      for (LabeledLocalTrigramContext labeledLocalTrigramContext : labeledLocalTrigramContexts) {
        String word = labeledLocalTrigramContext.getCurrentWord();
        String tag = labeledLocalTrigramContext.getCurrentTag();

        // Keep track of unknown tags and wordsToTags
        String charEndingMinor = ObtainLastNCharacters.ObtainLastNCharacters(word, 2);
        String charEndingSuperior = ObtainLastNCharacters.ObtainLastNCharacters(word, 3);

        //if (Character.isUpperCase(word.charAt(0))){
        minorSuffixCounter.incrementCount(charEndingMinor, tag, 1.0);
        superiorSuffixCounter.incrementCount(charEndingSuperior, tag, 1.0);

        tags.incrementCount(tag, 1.0);

        wordsToTags.incrementCount(word, tag, 1.0);

        // Keep track of bigram/trigram count
        seenTagTrigrams.incrementCount(makeTrigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag(), labeledLocalTrigramContext.getCurrentTag()), 1);
        seenTagBigrams.incrementCount(makeBigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag()), 1);
      }

      // Normalize the tag -> words map
      wordsToTags = Counters.conditionalNormalize(wordsToTags);
      minorSuffixCounter = Counters.conditionalNormalize(minorSuffixCounter);
      superiorSuffixCounter = Counters.conditionalNormalize(superiorSuffixCounter);
    }

    public void validate(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // no tuning for this dummy model!
    }

    public HMMTagScorerWithSuffixTrees(boolean restrictTrigrams) {
      this.restrictTrigrams = restrictTrigrams;
    }
  }


  /**
   * The HMM tag scorer implements HMM with unknown word classes to get a probability of a tag showing up
   */
  static class HMMTagScorerWithUnknownWordClasses implements LocalTrigramScorer {

    boolean restrictTrigrams; // if true, assign log score of Double.NEGATIVE_INFINITY to illegal tag trigrams.

    CounterMap<String, String> wordsToTags = new CounterMap<String, String>();
    Counter<String> unkCount = new Counter<String>();
    CounterMap<String, String> unknownBucketTags = new CounterMap<String, String>();
    Counter<String> seenTagTrigrams = new Counter<String>();
    Counter<String> seenTagBigrams = new Counter<String>();

    public int getHistorySize() {
      return 2;
    }

    public Counter<String> getLogScoreCounter(LocalTrigramContext localTrigramContext) {
      int position = localTrigramContext.getPosition();
      String word = localTrigramContext.getWords().get(position);

      // TagCounter will directly define the e(...)
      Counter<String> tagCounter;
      if (wordsToTags.keySet().contains(word)) {
        tagCounter = wordsToTags.getCounter(word);
      } else {
        String unknownWordBucket = PseudoWordClassifier.GetPseudoWord(word);
        tagCounter = unknownBucketTags.getCounter(unknownWordBucket);
      }

      // To calculate q(...), we'll need to evaluate trigram + bigram counts and divide
      Set<String> allowedFollowingTags = allowedFollowingTags(tagCounter.keySet(), localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      Counter<String> logScoreCounter = new Counter<String>();
      String bigramKey = makeBigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      double bigramCount = seenTagBigrams.getCount(bigramKey);
      for (String tag : tagCounter.keySet()) {
        if (!restrictTrigrams || allowedFollowingTags.isEmpty() || allowedFollowingTags.contains(tag)){
          String trigramKey = makeTrigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag(), tag);
          double trigramCount = seenTagTrigrams.getCount(trigramKey);
          double emissionProbability = tagCounter.getCount(tag);
          double logScore = Math.log(trigramCount / bigramCount * emissionProbability);

          if (Double.isNaN(logScore)){
            logScore = Double.NEGATIVE_INFINITY;
          }

          logScoreCounter.setCount(tag, logScore);
        }
      }
      return logScoreCounter;
    }

    private Set<String> allowedFollowingTags(Set<String> tags, String previousPreviousTag, String previousTag) {
      Set<String> allowedTags = new HashSet<String>();
      for (String tag : tags) {
        String trigramString = makeTrigramString(previousPreviousTag, previousTag, tag);
        if (seenTagTrigrams.containsKey((trigramString))) {
          allowedTags.add(tag);
        }
      }
      return allowedTags;
    }

    private String makeBigramString(String previousTag, String currentTag) {
      return previousTag + " " + currentTag;
    }

    private String makeTrigramString(String previousPreviousTag, String previousTag, String currentTag) {
      return previousPreviousTag + " " + previousTag + " " + currentTag;
    }

    public void train(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // collect word-tag counts
      for (LabeledLocalTrigramContext labeledLocalTrigramContext : labeledLocalTrigramContexts) {
        String word = labeledLocalTrigramContext.getCurrentWord();
        String tag = labeledLocalTrigramContext.getCurrentTag();

        // Keep track of unknown tags and wordsToTags
        unkCount.incrementCount(word, 1.0);
        if (unkCount.getCount(word) < 5)  { // This is the number of times that a word has to appear to be considered not unknown
          // word is currently unknown, so tally its tag in the unknown tag counter
          String unknownBucket = PseudoWordClassifier.GetPseudoWord(word);
          unknownBucketTags.incrementCount(unknownBucket, tag, 1.0);
        }
        wordsToTags.incrementCount(word, tag, 1.0);

        // Keep track of bigram/trigram count
        seenTagTrigrams.incrementCount(makeTrigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag(), labeledLocalTrigramContext.getCurrentTag()), 1);
        seenTagBigrams.incrementCount(makeBigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag()), 1);
      }

      // Normalize the tag -> words map
      wordsToTags = Counters.conditionalNormalize(wordsToTags);
      unknownBucketTags = Counters.conditionalNormalize(unknownBucketTags);
    }

    public void validate(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // no tuning for this dummy model!
    }

    public HMMTagScorerWithUnknownWordClasses(boolean restrictTrigrams) {
      this.restrictTrigrams = restrictTrigrams;
    }
  }

  /**
   * The HMM tag scorer implements HMM to get a probability of a tag showing up
   */
  static class HMMTagScorer implements LocalTrigramScorer {

    boolean restrictTrigrams; // if true, assign log score of Double.NEGATIVE_INFINITY to illegal tag trigrams.

    CounterMap<String, String> wordsToTags = new CounterMap<String, String>();
    Counter<String> unknownWordTags = new Counter<String>();
    Counter<String> seenTagTrigrams = new Counter<String>();
    Counter<String> seenTagBigrams = new Counter<String>();

    public int getHistorySize() {
      return 2;
    }

    public Counter<String> getLogScoreCounter(LocalTrigramContext localTrigramContext) {
      int position = localTrigramContext.getPosition();
      String word = localTrigramContext.getWords().get(position);

      // TagCounter will directly define the e(...)
      Counter<String> tagCounter = unknownWordTags;
      if (wordsToTags.keySet().contains(word)) {
        tagCounter = wordsToTags.getCounter(word);
      }

      // To calculate q(...), we'll need to evaluate trigram + bigram counts and divide
      Set<String> allowedFollowingTags = allowedFollowingTags(tagCounter.keySet(), localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      Counter<String> logScoreCounter = new Counter<String>();
      String bigramKey = makeBigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      double bigramCount = seenTagBigrams.getCount(bigramKey);
      for (String tag : tagCounter.keySet()) {
        if (!restrictTrigrams || allowedFollowingTags.isEmpty() || allowedFollowingTags.contains(tag)){
          String trigramKey = makeTrigramString(localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag(), tag);
          double trigramCount = seenTagTrigrams.getCount(trigramKey);
          double emissionProbability = tagCounter.getCount(tag);
          double logScore = Math.log(trigramCount / bigramCount * emissionProbability);
          logScoreCounter.setCount(tag, logScore);
        }
      }
      return logScoreCounter;
    }

    private Set<String> allowedFollowingTags(Set<String> tags, String previousPreviousTag, String previousTag) {
      Set<String> allowedTags = new HashSet<String>();
      for (String tag : tags) {
        String trigramString = makeTrigramString(previousPreviousTag, previousTag, tag);
        if (seenTagTrigrams.containsKey((trigramString))) {
          allowedTags.add(tag);
        }
      }
      return allowedTags;
    }

    private String makeBigramString(String previousTag, String currentTag) {
      return previousTag + " " + currentTag;
    }

    private String makeTrigramString(String previousPreviousTag, String previousTag, String currentTag) {
      return previousPreviousTag + " " + previousTag + " " + currentTag;
    }

    public void train(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // collect word-tag counts
      for (LabeledLocalTrigramContext labeledLocalTrigramContext : labeledLocalTrigramContexts) {
        String word = labeledLocalTrigramContext.getCurrentWord();
        String tag = labeledLocalTrigramContext.getCurrentTag();

        // Keep track of unknown tags and wordsToTags
        if (!wordsToTags.keySet().contains(word)) {
          // word is currently unknown, so tally its tag in the unknown tag counter
          unknownWordTags.incrementCount(tag, 1.0);
        }
        wordsToTags.incrementCount(word, tag, 1.0);

        // Keep track of bigram/trigram count
        seenTagTrigrams.incrementCount(makeTrigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag(), labeledLocalTrigramContext.getCurrentTag()), 1);
        seenTagBigrams.incrementCount(makeBigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag()), 1);
      }

      // Normalize the tag -> words map
      wordsToTags = Counters.conditionalNormalize(wordsToTags);
      unknownWordTags = Counters.normalize(unknownWordTags);
    }

    public void validate(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // no tuning for this dummy model!
    }

    public HMMTagScorer(boolean restrictTrigrams) {
      this.restrictTrigrams = restrictTrigrams;
    }
  }

  /**
   * The MostFrequentTagScorer gives each test word the tag it was seen with
   * most often in training (or the tag with the most seen word types if the
   * test word is unseen in training.  This scorer actually does a little more
   * than its name claims -- if constructed with restrictTrigrams = true, it
   * will forbid illegal tag trigrams, otherwise it makes no use of tag history
   * information whatsoever.
   */
  static class MostFrequentTagScorer implements LocalTrigramScorer {

    boolean restrictTrigrams; // if true, assign log score of Double.NEGATIVE_INFINITY to illegal tag trigrams.

    CounterMap<String, String> wordsToTags = new CounterMap<String, String>();
    Counter<String> unknownWordTags = new Counter<String>();
    Set<String> seenTagTrigrams = new HashSet<String>();

    public int getHistorySize() {
      return 2;
    }

    public Counter<String> getLogScoreCounter(LocalTrigramContext localTrigramContext) {
      int position = localTrigramContext.getPosition();
      String word = localTrigramContext.getWords().get(position);
      Counter<String> tagCounter = unknownWordTags;
      if (wordsToTags.keySet().contains(word)) {
        tagCounter = wordsToTags.getCounter(word);
      }
      Set<String> allowedFollowingTags = allowedFollowingTags(tagCounter.keySet(), localTrigramContext.getPreviousPreviousTag(), localTrigramContext.getPreviousTag());
      Counter<String> logScoreCounter = new Counter<String>();
      for (String tag : tagCounter.keySet()) {
        double logScore = Math.log(tagCounter.getCount(tag));
        if (!restrictTrigrams || allowedFollowingTags.isEmpty() || allowedFollowingTags.contains(tag))
          logScoreCounter.setCount(tag, logScore);
      }
      return logScoreCounter;
    }

    private Set<String> allowedFollowingTags(Set<String> tags, String previousPreviousTag, String previousTag) {
      Set<String> allowedTags = new HashSet<String>();
      for (String tag : tags) {
        String trigramString = makeTrigramString(previousPreviousTag, previousTag, tag);
        if (seenTagTrigrams.contains((trigramString))) {
          allowedTags.add(tag);
        }
      }
      return allowedTags;
    }

    private String makeTrigramString(String previousPreviousTag, String previousTag, String currentTag) {
      return previousPreviousTag + " " + previousTag + " " + currentTag;
    }

    public void train(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // collect word-tag counts
      for (LabeledLocalTrigramContext labeledLocalTrigramContext : labeledLocalTrigramContexts) {
        String word = labeledLocalTrigramContext.getCurrentWord();
        String tag = labeledLocalTrigramContext.getCurrentTag();
        if (!wordsToTags.keySet().contains(word)) {
          // word is currently unknown, so tally its tag in the unknown tag counter
          unknownWordTags.incrementCount(tag, 1.0);
        }
        wordsToTags.incrementCount(word, tag, 1.0);
        seenTagTrigrams.add(makeTrigramString(labeledLocalTrigramContext.getPreviousPreviousTag(), labeledLocalTrigramContext.getPreviousTag(), labeledLocalTrigramContext.getCurrentTag()));
      }
      wordsToTags = Counters.conditionalNormalize(wordsToTags);
      unknownWordTags = Counters.normalize(unknownWordTags);
    }

    public void validate(List<LabeledLocalTrigramContext> labeledLocalTrigramContexts) {
      // no tuning for this dummy model!
    }

    public MostFrequentTagScorer(boolean restrictTrigrams) {
      this.restrictTrigrams = restrictTrigrams;
    }
  }

  private static List<TaggedSentence> readTaggedSentences(String path, int low, int high) {
    Collection<Tree<String>> trees = PennTreebankReader.readTrees(path, low, high);
    List<TaggedSentence> taggedSentences = new ArrayList<TaggedSentence>();
    Trees.TreeTransformer<String> treeTransformer = new Trees.EmptyNodeStripper();
    for (Tree<String> tree : trees) {
      tree = treeTransformer.transformTree(tree);
      List<String> words = new BoundedList<String>(new ArrayList<String>(tree.getYield()), START_WORD, STOP_WORD);
      List<String> tags = new BoundedList<String>(new ArrayList<String>(tree.getPreTerminalYield()), START_TAG, STOP_TAG);
      taggedSentences.add(new TaggedSentence(words, tags));
    }
    return taggedSentences;
  }

  private static void evaluateTagger(POSTagger posTagger, List<TaggedSentence> taggedSentences, Set<String> trainingVocabulary, boolean verbose) {
    double numTags = 0.0;
    double numTagsCorrect = 0.0;
    double numUnknownWords = 0.0;
    double numUnknownWordsCorrect = 0.0;
    int numDecodingInversions = 0;
    for (TaggedSentence taggedSentence : taggedSentences) {
      List<String> words = taggedSentence.getWords();
      List<String> goldTags = taggedSentence.getTags();
      List<String> guessedTags = posTagger.tag(words);
      for (int position = 0; position < words.size() - 1; position++) {
        String word = words.get(position);
        String goldTag = goldTags.get(position);
        String guessedTag = guessedTags.get(position);
        if (guessedTag.equals(goldTag))
          numTagsCorrect += 1.0;
        numTags += 1.0;
        if (!trainingVocabulary.contains(word)) {
          if (guessedTag.equals(goldTag))
            numUnknownWordsCorrect += 1.0;
          numUnknownWords += 1.0;
        }
      }
      double scoreOfGoldTagging = posTagger.scoreTagging(taggedSentence);
      double scoreOfGuessedTagging = posTagger.scoreTagging(new TaggedSentence(words, guessedTags));
      if (scoreOfGoldTagging > scoreOfGuessedTagging) {
        numDecodingInversions++;
        if (verbose) System.out.println("WARNING: Decoder suboptimality detected.  Gold tagging has higher score than guessed tagging.");
      }
      if (verbose) System.out.println(alignedTaggings(words, goldTags, guessedTags, true) + "\n");
    }
    System.out.println("Tag Accuracy: " + (numTagsCorrect / numTags) + " (Unknown Accuracy: " + (numUnknownWordsCorrect / numUnknownWords) + ")  Decoder Suboptimalities Detected: " + numDecodingInversions);
  }

  // pretty-print a pair of taggings for a sentence, possibly suppressing the tags which correctly match
  private static String alignedTaggings(List<String> words, List<String> goldTags, List<String> guessedTags, boolean suppressCorrectTags) {
    StringBuilder goldSB = new StringBuilder("Gold Tags: ");
    StringBuilder guessedSB = new StringBuilder("Guessed Tags: ");
    StringBuilder wordSB = new StringBuilder("Words: ");
    for (int position = 0; position < words.size(); position++) {
      equalizeLengths(wordSB, goldSB, guessedSB);
      String word = words.get(position);
      String gold = goldTags.get(position);
      String guessed = guessedTags.get(position);
      wordSB.append(word);
      if (position < words.size() - 1)
        wordSB.append(' ');
      boolean correct = (gold.equals(guessed));
      if (correct && suppressCorrectTags)
        continue;
      guessedSB.append(guessed);
      goldSB.append(gold);
    }
    return goldSB + "\n" + guessedSB + "\n" + wordSB;
  }

  private static void equalizeLengths(StringBuilder sb1, StringBuilder sb2, StringBuilder sb3) {
    int maxLength = sb1.length();
    maxLength = Math.max(maxLength, sb2.length());
    maxLength = Math.max(maxLength, sb3.length());
    ensureLength(sb1, maxLength);
    ensureLength(sb2, maxLength);
    ensureLength(sb3, maxLength);
  }

  private static void ensureLength(StringBuilder sb, int length) {
    while (sb.length() < length) {
      sb.append(' ');
    }
  }

  private static Set<String> extractVocabulary(List<TaggedSentence> taggedSentences) {
    Set<String> vocabulary = new HashSet<String>();
    for (TaggedSentence taggedSentence : taggedSentences) {
      List<String> words = taggedSentence.getWords();
      vocabulary.addAll(words);
    }
    return vocabulary;
  }

  public static void main(String[] args) {
    // Parse command line flags and arguments
    Map<String, String> argMap = CommandLineUtils.simpleCommandLineParser(args);

    // Set up default parameters and settings
    String basePath = ".";
    boolean verbose = false;
    boolean useValidation = true;

    // Update defaults using command line specifications

    // The path to the assignment data
    if (argMap.containsKey("-path")) {
      basePath = argMap.get("-path");
    }
    System.out.println("Using base path: " + basePath);

    // Whether to use the validation or test set
    if (argMap.containsKey("-test")) {
      String testString = argMap.get("-test");
      if (testString.equalsIgnoreCase("test"))
        useValidation = false;
    }
    System.out.println("Testing on: " + (useValidation ? "validation" : "test"));

    // Whether or not to print the individual errors.
    if (argMap.containsKey("-verbose")) {
      verbose = true;
    }

    // Read in data
    System.out.print("Loading training sentences...");
    List<TaggedSentence> trainTaggedSentences = readTaggedSentences(basePath, 200, 2199);
    Set<String> trainingVocabulary = extractVocabulary(trainTaggedSentences);
    System.out.println("done.");
    System.out.print("Loading validation sentences...");
    List<TaggedSentence> validationTaggedSentences = readTaggedSentences(basePath, 2200, 2299);
    System.out.println("done.");
    System.out.print("Loading test sentences...");
    List<TaggedSentence> testTaggedSentences = readTaggedSentences(basePath, 2300, 2399);
    System.out.println("done.");

    long start = System.nanoTime();
    // Construct tagger components
    LocalTrigramScorer localTrigramScorer = new HMMTagScorerWithUnknownWordClasses(false);
    TrellisDecoder<State> trellisDecoder = new VitterbiDecoder<>();

    // Train tagger
    POSTagger posTagger = new POSTagger(localTrigramScorer, trellisDecoder);
    posTagger.train(trainTaggedSentences);
    posTagger.validate(validationTaggedSentences);

    // Evaluation set, use either test of validation (for dev)
    final List<TaggedSentence> evalTaggedSentences;
    if (useValidation) {
      evalTaggedSentences = validationTaggedSentences;
    } else {
      evalTaggedSentences = testTaggedSentences;
    }

    // Test tagger
    evaluateTagger(posTagger, evalTaggedSentences, trainingVocabulary, verbose);

    long elapsedNanos = System.nanoTime() - start;
    System.out.println("Ellapsed MS: " + (elapsedNanos / 1000000));
  }
}
