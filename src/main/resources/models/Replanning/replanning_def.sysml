package Replanning {
    private import ScalarValues::*;

    doc
    /* Replanning: how the digital twin reacts to an anomaly by moving machines of the SCADA to alternative missions,
       at once, without the operator's approval.

       A replanning strategy is triggered by an anomaly of the TokenLocation monitoring (a violated constraint, or the
       state in which it was detected). It stops the missions of the failed machines, and migrates each other machine
       it names from the mission it runs to an alternative mission.

       A mission migration reconciles a machine's state: the alternative mission starts in a state given by the
       machine's current state, and receives the feedback of the command in progress, if any. Each transition is a
       migration rule, to a state of the alternative mission ("then"):
         - "first" names the state of the running mission it leaves; without it, it leaves any state;
         - "accept when" guards it on the digital twin's observations (the TokenLocation attributes, e.g.
           cb.conveyorSensFeed, and tokenLocation.state, the monitored token location).
       The first rule (in the order of the model) leaving the machine's state whose guard holds is applied. If no rule
       leaves that state, the alternative mission starts in the state of the same name. A machine in one of its
       waitStates, or whose rules leaving its state all have a false guard, isn't migrated: the replanning waits for
       it to move on, and executes once every machine can be migrated.

       When the operator repairs the failure (resolves the incident), the strategy's recovery strategy is executed the
       same way: it migrates the machines back to their nominal missions and restarts the stopped ones. */

    state def MissionMigration {
        attribute machine : String;
        /* none: the machine runs no mission (it was stopped), toMission starts from its initial state */
        attribute fromMission : String[0..1];
        attribute toMission : String;
        /* the states of fromMission the machine isn't migrated from (e.g. it is notifying a zone the alternative
           mission already accounts for) */
        attribute waitStates : String[*];
    }

    part def ReplanningStrategy {
        attribute description : String;
        /* the constraints whose violation triggers the strategy (e.g. StateDurationLimits of TokenLocation) */
        attribute triggerConstraint : String[*];
        /* or the TokenLocation state in which the anomaly was detected */
        attribute triggerState : String;
        /* the machines whose missions are stopped (the failed ones) */
        attribute stoppedMachines : String[*];
        /* the MissionMigration applied, in this order */
        attribute migrations : String[*];
        /* the ReplanningStrategy executed once the operator repaired the failure: back to the nominal missions */
        attribute recoveryStrategy : String[0..1];
    }
}
