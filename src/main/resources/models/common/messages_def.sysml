package Common {
    part def Machine {
        metadata NoGeneration;
    }

    package Messages {
        item def BufferedMessage;
        item def EventMessage;
    }

    package CalcDefinitions { 
        doc 
        /*appel : IsInTolerance(gripperPosition.abstractPos, expectedPosition.abstractPos, tolerance )*/
        calc def IsInTolerance { in value : Natural; in expectedValue : Natural; in tolerance : Positive;
					return : Boolean = value  < expectedValue + tolerance and value  > expectedValue - tolerance;
				}
    }

    metadata def Workaround {
        doc
        /* This metadata indicates that the related element is part of a workaround for current limitation (usually in SysOn or in SysIDE). */
        attribute description : ScalarValues::String;
    }

    metadata def NoGeneration {}
}
