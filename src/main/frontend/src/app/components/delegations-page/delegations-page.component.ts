import { Component, OnInit, signal, computed, inject } from '@angular/core';
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
import { MatExpansionModule } from '@angular/material/expansion';
import { DatePipe } from '@angular/common';
import {
  DelegationService,
  DelegationResponse,
} from '../../services/delegation.service';
import { TargetSystemService, TargetSystem } from '../../services/target-system.service';
import { CfLookupService } from '../../services/cf-lookup.service';

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
    MatExpansionModule,
    DatePipe,
  ],
  templateUrl: './delegations-page.component.html',
  styleUrl: './delegations-page.component.scss',
})
export class DelegationsPageComponent implements OnInit {
  private delegationService = inject(DelegationService);
  private targetSystemService = inject(TargetSystemService);
  private cfLookupService = inject(CfLookupService);
  private snackBar = inject(MatSnackBar);

  protected readonly delegations = signal<DelegationResponse[]>([]);
  protected readonly targetSystems = signal<TargetSystem[]>([]);
  protected readonly loading = signal(true);
  protected readonly resolving = signal(false);
  protected readonly newlyCreatedToken = signal<string | null>(null);

  protected readonly activeDelegations = computed(() =>
    this.delegations().filter(d => !d.revoked && !this.isExpired(d))
  );

  protected readonly inactiveDelegations = computed(() =>
    this.delegations().filter(d => d.revoked || this.isExpired(d))
  );

  protected orgName = '';
  protected spaceName = '';
  protected appName = '';
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

  protected isFormValid(): boolean {
    return this.orgName.trim().length > 0
      && this.spaceName.trim().length > 0
      && this.appName.trim().length > 0;
  }

  protected parseWorkloadIdentity(agentId: string): { org: string; space: string; app: string } | null {
    const match = agentId.match(/^organization:(.+)\/space:(.+)\/app:(.+)$/);
    if (!match) return null;
    return { org: match[1], space: match[2], app: match[3] };
  }

  async createDelegation(): Promise<void> {
    if (!this.isFormValid() || this.selectedSystems.length === 0) return;

    this.resolving.set(true);
    try {
      const resolved = await this.cfLookupService.resolveWorkload(
        this.orgName.trim(), this.spaceName.trim(), this.appName.trim()
      );

      const agentId = `organization:${resolved.orgGuid}/space:${resolved.spaceGuid}/app:${resolved.appGuid}`;

      const result = await this.delegationService.createDelegation({
        agentId,
        allowedSystems: this.selectedSystems,
        ttlHours: this.ttlHours,
      });
      this.newlyCreatedToken.set(result.token);
      this.snackBar.open('Delegation token created', 'OK', { duration: 3000 });
      this.orgName = '';
      this.spaceName = '';
      this.appName = '';
      this.selectedSystems = [];
      this.loadData();
    } catch (err: any) {
      const message = err?.error?.error || 'Failed to create delegation';
      this.snackBar.open(message, 'OK', { duration: 5000 });
    } finally {
      this.resolving.set(false);
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
