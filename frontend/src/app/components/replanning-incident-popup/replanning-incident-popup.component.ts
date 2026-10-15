import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';

import { FormsModule } from '@angular/forms';

import { ReplanningIncident } from '../../models/replanning-incident.model';

@Component({
    selector: 'app-replanning-incident-popup',
    standalone: true,
    imports: [FormsModule],
    templateUrl: './replanning-incident-popup.component.html',
    styleUrls: ['./replanning-incident-popup.component.css']
})
export class ReplanningIncidentPopupComponent implements OnChanges {
    @Input({ required: true }) incident!: ReplanningIncident;
    @Output() validateCorrection = new EventEmitter<string>();

    comment = '';
    /** Only the header shows; a new incident opens expanded, updates of the same one keep the operator's choice. */
    collapsed = false;

    ngOnChanges(changes: SimpleChanges): void {
        const change = changes['incident'];
        if (change && change.previousValue?.id !== change.currentValue?.id) {
            this.collapsed = false;
        }
    }

    toggleCollapsed(): void {
        this.collapsed = !this.collapsed;
    }

    onValidateCorrection(): void {
        this.validateCorrection.emit(this.comment.trim());
        this.comment = '';
    }
}
