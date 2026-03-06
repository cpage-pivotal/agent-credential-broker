import { Routes } from '@angular/router';
import { GrantsDashboardComponent } from './components/grants-dashboard/grants-dashboard.component';
import { DelegationsPageComponent } from './components/delegations-page/delegations-page.component';

export const routes: Routes = [
  { path: '', redirectTo: 'grants', pathMatch: 'full' },
  { path: 'grants', component: GrantsDashboardComponent, title: 'Grants' },
  { path: 'delegations', component: DelegationsPageComponent, title: 'Delegations' },
];
