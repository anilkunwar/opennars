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
package org.opennars.plugin.mental;

import org.opennars.control.DerivationContext;
import org.opennars.entity.*;
import org.opennars.inference.BudgetFunctions;
import org.opennars.inference.TemporalRules;
import org.opennars.io.Symbols;
import org.opennars.io.events.EventEmitter.EventObserver;
import org.opennars.io.events.Events;
import org.opennars.language.*;
import org.opennars.main.Nar;
import org.opennars.main.Parameters;
import org.opennars.operator.Operation;
import org.opennars.operator.Operator;
import org.opennars.plugin.Plugin;
import org.opennars.storage.Memory;

import java.util.Arrays;

/**
 * To rememberAction an internal action as an operation
 * <p>
 * called from Concept
 * @param task The task processed
 */
public class InternalExperience implements Plugin, EventObserver {
        
    public static float MINIMUM_PRIORITY_TO_CREATE_WANT_BELIEVE_ETC=0.3f;
    public static float MINIMUM_PRIORITY_TO_CREATE_WONDER_EVALUATE=0.3f;
    public static final float MINIMUM_CONCEPT_PRIORITY_TO_CREATE_ANTICIPATION=0.01f;
    
    //internal experience has less durability?
    public static final float INTERNAL_EXPERIENCE_PROBABILITY=0.0001f;
    
    //less probable form
    public static final float INTERNAL_EXPERIENCE_RARE_PROBABILITY = 
            INTERNAL_EXPERIENCE_PROBABILITY/4f;
    
    //internal experience has less durability?
    public static final float INTERNAL_EXPERIENCE_DURABILITY_MUL=0.1f; //0.1
    //internal experience has less priority?
    public static final float INTERNAL_EXPERIENCE_PRIORITY_MUL=0.1f; //0.1
    
    //dont use internal experience for want and believe if this setting is true
    public static boolean AllowWantBelieve=true; 
    
    //
    public static boolean OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY=false; //https://groups.google.com/forum/#!topic/open-nars/DVE5FJd7FaM
    
    public boolean isAllowNewStrategy() {
        return !OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY;
    }
    public void setAllowNewStrategy(final boolean val) {
        OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY=!val;
    }
    
    public boolean isAllowWantBelieve() {
        return AllowWantBelieve;
    }
    public void setAllowWantBelieve(final boolean val) {
        AllowWantBelieve=val;
    }

    
    public double getMinCreationBudgetSummary() {
        return MINIMUM_PRIORITY_TO_CREATE_WANT_BELIEVE_ETC;
    }
    public void setMinCreationBudgetSummary(final double val) {
        MINIMUM_PRIORITY_TO_CREATE_WANT_BELIEVE_ETC=(float) val;
    }
    
    public double getMinCreationBudgetSummaryWonderEvaluate() {
        return MINIMUM_PRIORITY_TO_CREATE_WONDER_EVALUATE;
    }
    public void setMinCreationBudgetSummaryWonderEvaluate(final double val) {
        MINIMUM_PRIORITY_TO_CREATE_WONDER_EVALUATE=(float) val;
    }
    
    private Memory memory;


    /** whether it is full internal experience, or minimal (false) */
    public boolean isFull() {
        return false;
    }
    
    public static boolean enabled=false;
    
    @Override public boolean setEnabled(final Nar n, final boolean enable) {
        memory = n.memory;
        
        memory.event.set(this, enable, Events.ConceptDirectProcessedTask.class);
        
        if (isFull())
            memory.event.set(this, enable, Events.BeliefReason.class);
        
        enabled=enable;
        
        return true;
    }
    
        public static Term toTerm(final Sentence s, final Memory mem) {
        final String opName;
        switch (s.punctuation) {
            case Symbols.JUDGMENT_MARK:
                opName = "^believe";
                if(!AllowWantBelieve) {
                    return null;
                }
                break;
            case Symbols.GOAL_MARK:
                opName = "^want";
                if(!AllowWantBelieve) {
                    return null;
                }
                break;
            case Symbols.QUESTION_MARK:
                opName = "^wonder";
                break;
            case Symbols.QUEST_MARK:
                opName = "^evaluate";
                break;
            default:
                return null;
        }
        
        final Term opTerm = mem.getOperator(opName);
        final Term[] arg = new Term[ s.truth==null ? 2 : 3 ];
        arg[0]=Term.SELF;
        arg[1]=s.getTerm();
        if (s.truth != null) {
            arg[2] = s.projection(mem.time(), mem.time()).truth.toWordTerm();            
        }
        
        //Operation.make ?
        final Term operation = Inheritance.make(new Product(arg), opTerm);
        if (operation == null) {
            throw new IllegalStateException("Unable to create Inheritance: " + opTerm + ", " + Arrays.toString(arg));
        }
        return operation;
    }


    @Override
    public void event(final Class event, final Object[] a) {
        
        if (event==Events.ConceptDirectProcessedTask.class) {
            final Task task = (Task)a[0];
            
            //old strategy always, new strategy only for QUESTION and QUEST:
            if(OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY || (!OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY && (task.sentence.punctuation == Symbols.QUESTION_MARK || task.sentence.punctuation == Symbols.QUEST_MARK))) {
                InternalExperienceFromTaskInternal(memory,task,isFull());
            }
        }
        else if (event == Events.BeliefReason.class) {
            //belief, beliefTerm, taskTerm, nal
            final Sentence belief = (Sentence)a[0];
            final Term beliefTerm = (Term)a[1];
            final Term taskTerm = (Term)a[2];
            final DerivationContext nal = (DerivationContext)a[3];
            beliefReason(belief, beliefTerm, taskTerm, nal);
        }
    }
    
    public static void InternalExperienceFromBelief(final Memory memory, final Task task, final Sentence belief) {
        final Task T=new Task(belief.clone(),task.budget.clone(),true);
        InternalExperienceFromTask(memory,T,false);
    }
    
    public static void InternalExperienceFromTask(final Memory memory, final Task task, final boolean full) {
        if(!OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY) {
            InternalExperienceFromTaskInternal(memory,task,full);
        }
    }

    public static boolean InternalExperienceFromTaskInternal(final Memory memory, final Task task, final boolean full) {
        if(!enabled) {
            return false;
        }
        
       // if(OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY ||
       //         (!OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY && (task.sentence.punctuation==Symbols.QUESTION_MARK || task.sentence.punctuation==Symbols.QUEST_MARK))) {
        {
            if(task.sentence.punctuation == Symbols.QUESTION_MARK || task.sentence.punctuation == Symbols.QUEST_MARK) {
                if(task.getPriority()<MINIMUM_PRIORITY_TO_CREATE_WONDER_EVALUATE) {
                    return false;
                }
            }
            else
            if(task.getPriority()<MINIMUM_PRIORITY_TO_CREATE_WANT_BELIEVE_ETC) {
                return false;
            }
        }
        
        final Term content=task.getTerm();
        // to prevent infinite recursions
        if (content instanceof Operation/* ||  Memory.randomNumber.nextDouble()>Parameters.INTERNAL_EXPERIENCE_PROBABILITY*/) {
            return true;
        }
        final Sentence sentence = task.sentence;
        final TruthValue truth = new TruthValue(1.0f, Parameters.DEFAULT_JUDGMENT_CONFIDENCE);
        final Stamp stamp = task.sentence.stamp.clone();
        stamp.setOccurrenceTime(memory.time());
        final Term ret=toTerm(sentence, memory);
        if (ret==null) {
            return true;
        }
        final Sentence j = new Sentence(
            ret,
            Symbols.JUDGMENT_MARK,
            truth,
            stamp);

        final BudgetValue newbudget=new BudgetValue(
                Parameters.DEFAULT_JUDGMENT_CONFIDENCE*INTERNAL_EXPERIENCE_PRIORITY_MUL,
                Parameters.DEFAULT_JUDGMENT_PRIORITY*INTERNAL_EXPERIENCE_DURABILITY_MUL,
                BudgetFunctions.truthToQuality(truth));
        
        if(!OLD_BELIEVE_WANT_EVALUATE_WONDER_STRATEGY) {
            newbudget.setPriority(task.getPriority()*INTERNAL_EXPERIENCE_PRIORITY_MUL);
            newbudget.setDurability(task.getDurability()*INTERNAL_EXPERIENCE_DURABILITY_MUL);
        }
        
        final Task newTask = new Task(j, newbudget, true);
        memory.addNewTask(newTask, "Reflected mental operation (Internal Experience)");
        return false;
    }

    final static String[] nonInnateBeliefOperators = new String[] {
        "^remind","^doubt","^consider","^evaluate","hestitate","^wonder","^belief","^want"
    }; 
    
    /** used in full internal experience mode only */
    protected void beliefReason(final Sentence belief, final Term beliefTerm, final Term taskTerm, final DerivationContext nal) {
        
        final Memory memory = nal.memory;
    
        if (Memory.randomNumber.nextDouble() < INTERNAL_EXPERIENCE_RARE_PROBABILITY ) {
            
            //the operators which dont have a innate belief
            //also get a chance to reveal its effects to the system this way
            final Operator op=memory.getOperator(nonInnateBeliefOperators[Memory.randomNumber.nextInt(nonInnateBeliefOperators.length)]);
            
            final Product prod=new Product(belief.term);
            
            if(op!=null && prod!=null) {
                
                final Term new_term=Inheritance.make(prod, op);
                final Sentence sentence = new Sentence(
                    new_term,
                    Symbols.GOAL_MARK,
                    new TruthValue(1, Parameters.DEFAULT_JUDGMENT_CONFIDENCE),  // a naming convension
                    new Stamp(memory));
                
                final float quality = BudgetFunctions.truthToQuality(sentence.truth);
                final BudgetValue budget = new BudgetValue(
                    Parameters.DEFAULT_GOAL_PRIORITY*INTERNAL_EXPERIENCE_PRIORITY_MUL, 
                    Parameters.DEFAULT_GOAL_DURABILITY*INTERNAL_EXPERIENCE_DURABILITY_MUL, 
                    quality);

                final Task newTask = new Task(sentence, budget, true);
                nal.derivedTask(newTask, false, false, false);
            }
        }

        if (beliefTerm instanceof Implication && Memory.randomNumber.nextDouble()<=INTERNAL_EXPERIENCE_PROBABILITY) {
            final Implication imp=(Implication) beliefTerm;
            if(imp.getTemporalOrder()==TemporalRules.ORDER_FORWARD) {
                //1. check if its (&/,term,+i1,...,+in) =/> anticipateTerm form:
                boolean valid=true;
                if(imp.getSubject() instanceof Conjunction) {
                    final Conjunction conj=(Conjunction) imp.getSubject();
                    if(!conj.term[0].equals(taskTerm)) {
                        valid=false; //the expected needed term is not included
                    }
                    for(int i=1;i<conj.term.length;i++) {
                        if(!(conj.term[i] instanceof Interval)) {
                            valid=false;
                            break;
                        }
                    }
                } else {
                    if(!imp.getSubject().equals(taskTerm)) {
                        valid=false;
                    }
                }    

                if(valid) {
                    final Operator op=memory.getOperator("^anticipate");
                    if (op == null)
                        throw new IllegalStateException(this + " requires ^anticipate operator");
                    
                    final Product args=new Product(imp.getPredicate());
                    final Term new_term=Operation.make(args,op);

                    final Sentence sentence = new Sentence(
                        new_term,
                        Symbols.GOAL_MARK,
                        new TruthValue(1, Parameters.DEFAULT_JUDGMENT_CONFIDENCE),  // a naming convension
                        new Stamp(memory));

                    final float quality = BudgetFunctions.truthToQuality(sentence.truth);
                    final BudgetValue budget = new BudgetValue(
                        Parameters.DEFAULT_GOAL_PRIORITY*INTERNAL_EXPERIENCE_PRIORITY_MUL, 
                        Parameters.DEFAULT_GOAL_DURABILITY*INTERNAL_EXPERIENCE_DURABILITY_MUL, 
                        quality);

                    final Task newTask = new Task(sentence, budget, true);
                    nal.derivedTask(newTask, false, false, false);
                }
            }
        }
    }    
}
