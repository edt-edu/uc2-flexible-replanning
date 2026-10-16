import { Routes } from '@angular/router';

import { FactorySupervisionComponent } from './pages/factory-supervision/factory-supervision.component';

export const routes: Routes = [
    { path: '', component: FactorySupervisionComponent },
    { path: '**', redirectTo: '' }
];
