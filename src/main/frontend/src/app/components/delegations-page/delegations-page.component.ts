import { Component, OnInit, signal, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';
import {
  DelegationService,
  DelegationResponse,
} from '../../services/delegation.service';
import { TargetSystemService, TargetSystem } from '../../services/target-system.service';

@Component({
  selector: 'app-delegations-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule,
    MatTooltipModule,
    DatePipe,
  ],
  templateUrl: './delegations-page.component.html',
  styleUrl: './delegations-page.component.scss',
})
export class DelegationsPageComponent implements OnInit {
  private delegationService = inject(DelegationService);
  private targetSystemService = inject(TargetSystemService);
  private snackBar = inject(MatSnackBar);

  protected readonly delegations = signal<DelegationResponse[]>([]);
  protected readonly targetSystems = signal<TargetSystem[]>([]);
  protected readonly loading = signal(true);
  protected readonly newlyCreatedToken = signal<string | null>(null);

  protected agentId = '';
  protected selectedSystems: string[] = [];
  protected ttlHours = 72;

  ngOnInit(): void {
    this.loadData();
  }

  async loadData(): Promise<void> {
    this.loading.set(true);
    try {
      const [delegations, systems] = await Promise.all([
        this.delegationService.listDelegations(),
        this.targetSystemService.listTargetSystems(),
      ]);
      this.delegations.set(delegations);
      this.targetSystems.set(systems);
    } finally {
      this.loading.set(false);
    }
  }

  async createDelegation(): Promise<void> {
    if (!this.agentId.trim() || this.selectedSystems.length === 0) return;

    try {
      const result = await this.delegationService.createDelegation({
        agentId: this.agentId.trim(),
        allowedSystems: this.selectedSystems,
        ttlHours: this.ttlHours,
      });
      this.newlyCreatedToken.set(result.token);
      this.snackBar.open('Delegation token created', 'OK', { duration: 3000 });
      this.agentId = '';
      this.selectedSystems = [];
      this.loadData();
    } catch {
      this.snackBar.open('Failed to create delegation', 'OK', { duration: 5000 });
    }
  }

  async copyToken(token: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(token);
      this.snackBar.open('Token copied to clipboard', 'OK', { duration: 2000 });
    } catch {
      this.snackBar.open('Failed to copy token', 'OK', { duration: 3000 });
    }
  }

  async revoke(id: string): Promise<void> {
    try {
      await this.delegationService.revokeDelegation(id);
      this.snackBar.open('Delegation revoked', 'OK', { duration: 3000 });
      this.loadData();
    } catch {
      this.snackBar.open('Failed to revoke delegation', 'OK', { duration: 5000 });
    }
  }

  async refresh(id: string): Promise<void> {
    try {
      const result = await this.delegationService.refreshDelegation(id);
      this.newlyCreatedToken.set(result.token);
      this.snackBar.open('Delegation refreshed', 'OK', { duration: 3000 });
      this.loadData();
    } catch {
      this.snackBar.open('Failed to refresh delegation', 'OK', { duration: 5000 });
    }
  }

  isExpired(delegation: DelegationResponse): boolean {
    return new Date(delegation.expiresAt) < new Date();
  }

  delegationStatus(delegation: DelegationResponse): string {
    if (delegation.revoked) return 'Revoked';
    if (this.isExpired(delegation)) return 'Expired';
    return 'Active';
  }

  dismissToken(): void {
    this.newlyCreatedToken.set(null);
  }
}
