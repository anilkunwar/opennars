/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.opennars.control.concept;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.opennars.control.DerivationContext;
import org.opennars.entity.BudgetValue;
import org.opennars.entity.Concept;
import org.opennars.entity.Sentence;
import org.opennars.entity.Stamp;
import org.opennars.entity.Task;
import org.opennars.entity.TruthValue;
import static org.opennars.inference.LocalRules.revisible;
import static org.opennars.inference.LocalRules.revision;
import static org.opennars.inference.LocalRules.trySolution;
import org.opennars.inference.TemporalRules;
import org.opennars.inference.TruthFunctions;
import org.opennars.io.Symbols;
import org.opennars.io.events.Events;
import org.opennars.language.Conjunction;
import org.opennars.language.Equivalence;
import org.opennars.language.Implication;
import org.opennars.language.Interval;
import org.opennars.language.Product;
import org.opennars.language.Term;
import org.opennars.language.Variable;
import org.opennars.main.Parameters;
import org.opennars.operator.FunctionOperator;
import org.opennars.operator.Operation;
import org.opennars.operator.Operator;
import org.opennars.plugin.mental.InternalExperience;

/**
 *
 * @author Patrick
 */
public class ProcessGoal {
    /**
     * To accept a new goal, and check for revisions and realization, then
     * decide whether to actively pursue it, potentially executing in case of an operation goal
     *
     * @param concept The concept of the goal
     * @param nal The derivation context
     * @param task The goal task to be processed
     * @return Whether to continue the processing of the task
     */
    protected static void processGoal(final Concept concept, final DerivationContext nal, final Task task) {
        final Sentence goal = task.sentence;
        final Task oldGoalT = concept.selectCandidate(task, concept.desires); // revise with the existing desire values
        Sentence oldGoal = null;
        final Stamp newStamp = goal.stamp;
        if (oldGoalT != null) {
            oldGoal = oldGoalT.sentence;
            final Stamp oldStamp = oldGoal.stamp;
            if (newStamp.equals(oldStamp,false,false,true)) {
                return; // duplicate
            }
        }
        Task beliefT = null;
        if(task.aboveThreshold()) {
            beliefT = concept.selectCandidate(task, concept.beliefs); // check if the Goal is already satisfied
            final int nnq = concept.quests.size();
            for (int i = 0; i < nnq; i++) {
                trySolution(task.sentence, concept.quests.get(i), nal, true);
            }
            if (beliefT != null) { 
                trySolution(beliefT.sentence, task, nal, true); // check if the Goal is already satisfied (manipulate budget)
            }
        }
        if (oldGoalT != null) {
            if (revisible(goal, oldGoal)) {
                final Stamp oldStamp = oldGoal.stamp;
                nal.setTheNewStamp(newStamp, oldStamp, concept.memory.time());
                final Sentence projectedGoal = oldGoal.projection(task.sentence.getOccurenceTime(), newStamp.getOccurrenceTime());
                if (projectedGoal!=null) {
                    nal.setCurrentBelief(projectedGoal);
                    final boolean successOfRevision=revision(task.sentence, projectedGoal, false, nal);
                    if(successOfRevision) { // it is revised, so there is a new task for which this function will be called
                        return; // with higher/lower desire
                    } //it is not allowed to go on directly due to decision making https://groups.google.com/forum/#!topic/open-nars/lQD0no2ovx4
                }
            }
        }
        final Stamp s2=goal.stamp.clone();
        s2.setOccurrenceTime(concept.memory.time());
        if(s2.after(task.sentence.stamp, Parameters.DURATION)) { //this task is not up to date we have to project it first
            final Sentence projGoal = task.sentence.projection(concept.memory.time(), Parameters.DURATION);
            if(projGoal!=null && projGoal.truth.getExpectation() > nal.narParameters.DECISION_THRESHOLD) {
                nal.singlePremiseTask(projGoal, task.budget.clone()); //keep goal updated
                // return false; //outcommented, allowing "roundtrips now", relevant for executing multiple steps of learned implication chains
            }
        }
        if (task.aboveThreshold()) {
            double AntiSatisfaction = 0.5f; //we dont know anything about that goal yet, so we pursue it to remember it because its maximally unsatisfied
            if (beliefT != null) {
                final Sentence belief = beliefT.sentence;
                final Sentence projectedBelief = belief.projection(task.sentence.getOccurenceTime(), Parameters.DURATION);
                AntiSatisfaction = task.sentence.truth.getExpDifAbs(projectedBelief.truth);
            }
            final double Satisfaction=1.0-AntiSatisfaction;
            task.setPriority(task.getPriority()* (float)AntiSatisfaction);
            if (!task.aboveThreshold()) {
                return;
            }
            final TruthValue T=goal.truth.clone();
            T.setFrequency((float) (T.getFrequency()-Satisfaction)); //decrease frequency according to satisfaction value
            final boolean fullfilled = AntiSatisfaction < Parameters.SATISFACTION_TRESHOLD;
            final Sentence projectedGoal = goal.projection(nal.memory.time(),nal.memory.time());
            if (!(projectedGoal != null && task.aboveThreshold() && !fullfilled)) {
                return;
            }
            bestReactionForGoal(concept, nal, projectedGoal, task);
            questionFromGoal(task, nal);
            concept.addToTable(task, false, concept.desires, Parameters.CONCEPT_GOALS_MAX, Events.ConceptGoalAdd.class, Events.ConceptGoalRemove.class);
            InternalExperience.InternalExperienceFromTask(concept.memory,task,false);
            if(!(task.sentence.getTerm() instanceof Operation)) {
                return;
            }
            processOperationGoal(projectedGoal, nal, concept, oldGoalT, task);
        }
    }

    /**
     * To process an operation for potential execution
     * only called by processGoal
     * 
     * @param projectedGoal The current goal
     * @param nal The derivation context
     * @param concept The concept of the current goal
     * @param oldGoalT The best goal in the goal table
     * @param Task The current goal task
     */
    protected static void processOperationGoal(final Sentence projectedGoal, final DerivationContext nal, final Concept concept, final Task oldGoalT, final Task task) {
        if(projectedGoal.truth.getExpectation() > nal.narParameters.DECISION_THRESHOLD && nal.memory.time() >= concept.memory.decisionBlock) {
            //see whether the goal evidence is fully included in the old goal, if yes don't execute
            //as execution for this reason already happened (or did not since there was evidence against it)
            final Set<Long> oldEvidence = new HashSet<>();
            boolean Subset=false;
            if(oldGoalT != null) {
                Subset = true;
                for(final Long l: oldGoalT.sentence.stamp.evidentialBase) {
                    oldEvidence.add(l);
                }
                for(final Long l: task.sentence.stamp.evidentialBase) {
                    if(!oldEvidence.contains(l)) {
                        Subset = false;
                        break;
                    }
                }
            }
            if(!Subset && !executeOperation(nal, task)) {
                concept.memory.emit(Events.UnexecutableGoal.class, task, concept, nal);
                return; //it was made true by itself
            }
        }
    }
    
    /**
     * Generate <?how =/> g>? question for g! goal.
     * only called by processGoal
     * 
     * @param Task The current goal task
     * @param nal The derivation context
     */    
    public static void questionFromGoal(final Task task, final DerivationContext nal) {
        if(Parameters.QUESTION_GENERATION_ON_DECISION_MAKING || Parameters.HOW_QUESTION_GENERATION_ON_DECISION_MAKING) {
            //ok, how can we achieve it? add a question of whether it is fullfilled
            final List<Term> qu= new ArrayList<>();
            if(Parameters.HOW_QUESTION_GENERATION_ON_DECISION_MAKING) {
                if(!(task.sentence.term instanceof Equivalence) && !(task.sentence.term instanceof Implication)) {
                    final Variable how=new Variable("?how");
                    //Implication imp=Implication.make(how, task.sentence.term, TemporalRules.ORDER_CONCURRENT);
                    final Implication imp2=Implication.make(how, task.sentence.term, TemporalRules.ORDER_FORWARD);
                    //qu.add(imp);
                    if(!(task.sentence.term instanceof Operation)) {
                        qu.add(imp2);
                    }
                }
            }
            if(Parameters.QUESTION_GENERATION_ON_DECISION_MAKING) {
                qu.add(task.sentence.term);
            }
            for(final Term q : qu) {
                if(q!=null) {
                    final Stamp st = new Stamp(task.sentence.stamp,nal.memory.time());
                    st.setOccurrenceTime(task.sentence.getOccurenceTime()); //set tense of question to goal tense
                    final Sentence s = new Sentence(
                        q,
                        Symbols.QUESTION_MARK,
                        null,
                        st);

                    if(s!=null) {
                        final BudgetValue budget=new BudgetValue(task.getPriority()*Parameters.CURIOSITY_DESIRE_PRIORITY_MUL,task.getDurability()*Parameters.CURIOSITY_DESIRE_DURABILITY_MUL,1);
                        nal.singlePremiseTask(s, budget);
                    }
                }
            }
        }
    }
    
    private static class ExecutablePrecondition {
        public Operation bestop = null;
        public float bestop_truthexp = 0.0f;
        public TruthValue bestop_truth = null;
        public Task executable_precond = null;
        public long mintime = -1;
        public long maxtime = -1;
    }
    
    /**
    * When a goal is processed, use the best memorized reaction
    * that is applicable to the current context (recent events) in case that it exists.
    * This is a special case of the choice rule and allows certain behaviors to be automated.
    * 
    * @param concept The concept of the goal to realize
    * @param nal The derivation context
    * @param projectedGoal The current goal
    * @param task The goal task
    */
    protected static void bestReactionForGoal(final Concept concept, final DerivationContext nal, final Sentence projectedGoal, final Task task) {
        ExecutablePrecondition bestOpWithMeta = calcBestExecutablePrecondition(nal, concept, projectedGoal);
        executePrecondition(nal, bestOpWithMeta, concept, projectedGoal, task);
    }
       
    private static ExecutablePrecondition calcBestExecutablePrecondition(final DerivationContext nal, final Concept concept, final Sentence projectedGoal) {
        ExecutablePrecondition result = new ExecutablePrecondition();
        for(final Task t: concept.executable_preconditions) {
            final Term[] prec = ((Conjunction) ((Implication) t.getTerm()).getSubject()).term;
            final Term[] newprec = new Term[prec.length-3];
            System.arraycopy(prec, 0, newprec, 0, prec.length - 3);
            final long add_tolerance = (long) (((Interval)prec[prec.length-1]).time*Parameters.ANTICIPATION_TOLERANCE);
            result.mintime = nal.memory.time();
            result.maxtime = nal.memory.time() + add_tolerance;
            final Operation op = (Operation) prec[prec.length-2];
            final Term precondition = Conjunction.make(newprec,TemporalRules.ORDER_FORWARD);
            final Concept preconc = nal.memory.concept(precondition);
            long newesttime = -1;
            Task bestsofar = null;
            if(preconc == null) {
                continue;
            }
            //ok we can look now how much it is fullfilled
            //check recent events in event bag
            for(final Task p : concept.memory.seq_current) {
                if(p.sentence.term.equals(preconc.term) && p.sentence.isJudgment() && !p.sentence.isEternal() && p.sentence.getOccurenceTime() > newesttime  && p.sentence.getOccurenceTime() <= concept.memory.time()) {
                    newesttime = p.sentence.getOccurenceTime();
                    bestsofar = p; //we use the newest for now
                }
            }
            if(bestsofar == null) {
                continue;
            }
            //ok now we can take the desire value:
            final TruthValue A = projectedGoal.getTruth();
            //and the truth of the hypothesis:
            final TruthValue Hyp = t.sentence.truth;
            //overlap will almost never happen, but to make sure
            if(Stamp.baseOverlap(projectedGoal.stamp.evidentialBase, t.sentence.stamp.evidentialBase) ||
               Stamp.baseOverlap(bestsofar.sentence.stamp.evidentialBase, t.sentence.stamp.evidentialBase) ||
               Stamp.baseOverlap(projectedGoal.stamp.evidentialBase, bestsofar.sentence.stamp.evidentialBase)) {
                continue;
            }
            //and the truth of the precondition:
            final Sentence projectedPrecon = bestsofar.sentence.projection(concept.memory.time() /*- distance*/, concept.memory.time());
            if(projectedPrecon.isEternal()) {
                continue; //projection wasn't better than eternalization, too long in the past
            }
            final TruthValue precon = projectedPrecon.truth;
            //and derive the conjunction of the left side:
            final TruthValue leftside = TruthFunctions.desireDed(A, Hyp);
            //in order to derive the operator desire value:
            final TruthValue opdesire = TruthFunctions.desireDed(precon, leftside);
            final float expecdesire = opdesire.getExpectation();
            if(expecdesire > result.bestop_truthexp) {
                result.bestop = op;
                result.bestop_truthexp = expecdesire;
                result.bestop_truth = opdesire;
                result.executable_precond = t;
            }
        }
        return result;
    }
    
    private static void executePrecondition(final DerivationContext nal, ExecutablePrecondition meta, final Concept concept, final Sentence projectedGoal, final Task task) {
        if(meta.bestop != null && meta.bestop_truthexp > nal.narParameters.DECISION_THRESHOLD /*&& Math.random() < bestop_truthexp */) {
            final Sentence createdSentence = new Sentence(
                meta.bestop,
                Symbols.JUDGMENT_MARK,
                meta.bestop_truth,
                projectedGoal.stamp);
            final Task t = new Task(createdSentence,
                new BudgetValue(1.0f,1.0f,1.0f),
                false);
            //System.out.println("used " +t.getTerm().toString() + String.valueOf(memory.randomNumber.nextInt()));
            if(!task.sentence.stamp.evidenceIsCyclic()) {
                if(!executeOperation(nal, t)) { //this task is just used as dummy
                    concept.memory.emit(Events.UnexecutableGoal.class, task, concept, nal);
                    return;
                }
                concept.memory.decisionBlock = concept.memory.time() + Parameters.AUTOMATIC_DECISION_USUAL_DECISION_BLOCK_CYCLES;
                ProcessAnticipation.anticipate(nal, meta.executable_precond.sentence, meta.executable_precond.budget, meta.mintime, meta.maxtime, 2);
            }
        }
    }
    
    /**
     * Entry point for all potentially executable operation tasks.
     * Returns true if the Task has a Term which can be executed
     * 
     * @param nal The derivation concept
     * @param t The operation goal task
     */
    public static boolean executeOperation(final DerivationContext nal, final Task t) {        
        final Term content = t.getTerm();
        if(!(nal.memory.allowExecution) || !(content instanceof Operation)) {
            return false;
        }  
        final Operation op=(Operation)content;
        final Operator oper = op.getOperator();
        final Product prod = (Product) op.getSubject();
        final Term arg = prod.term[0];
        if(oper instanceof FunctionOperator) {
            for(int i=0;i<prod.term.length-1;i++) { //except last one, the output arg
                if(prod.term[i].hasVarDep() || prod.term[i].hasVarIndep()) {
                    return false;
                }
            }
        } else {
            if(content.hasVarDep() || content.hasVarIndep()) {
                return false;
            }
        }
        if(!arg.equals(Term.SELF)) { //will be deprecated in the future
            return false;
        }

        op.setTask(t);
        if(!oper.call(op, nal.memory)) {
            return false;
        }
        if (Parameters.DEBUG) {
            System.out.println(t.toStringLong());
        }
        return true;
    }
}
