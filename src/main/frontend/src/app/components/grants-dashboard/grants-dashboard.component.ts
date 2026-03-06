import { Component, OnInit, signal, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { LowerCasePipe } from '@angular/common';
import { GrantService, UserGrant } from '../../services/grant.service';
import { TokenEntryDialogComponent } from '../token-entry-dialog/token-entry-dialog.component';

@Component({
  selector: 'app-grants-dashboard',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule,
    LowerCasePipe,
  ],
  templateUrl: './grants-dashboard.component.html',
  styleUrl: './grants-dashboard.component.scss',
})
export class GrantsDashboardComponent implements OnInit {
  private grantService = inject(GrantService);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  protected readonly grants = signal<UserGrant[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.loadGrants();
  }

  async loadGrants(): Promise<void> {
    this.loading.set(true);
    try {
      const grants = await this.grantService.listGrants();
      this.grants.set(grants);
    } finally {
      this.loading.set(false);
    }
  }

  async authorize(grant: UserGrant): Promise<void> {
    try {
      const authUrl = await this.grantService.initiateOAuthGrant(grant.targetSystem);
      const popup = window.open(authUrl, 'oauth-popup', 'width=600,height=700');

      const handler = (event: MessageEvent) => {
        if (event.data?.type === 'oauth-callback' && event.data?.status === 'success') {
          window.removeEventListener('message', handler);
          this.snackBar.open(`Connected to ${grant.targetSystem}`, 'OK', { duration: 3000 });
          this.loadGrants();
        }
      };
      window.addEventListener('message', handler);

      const timer = setInterval(() => {
        if (popup?.closed) {
          clearInterval(timer);
          window.removeEventListener('message', handler);
          this.loadGrants();
        }
      }, 500);
    } catch {
      this.snackBar.open(`Failed to initiate authorization for ${grant.targetSystem}`, 'OK', {
        duration: 5000,
      });
    }
  }

  enterToken(grant: UserGrant): void {
    const dialogRef = this.dialog.open(TokenEntryDialogComponent, {
      width: '480px',
      data: { targetSystem: grant.targetSystem, description: grant.description },
    });

    dialogRef.afterClosed().subscribe(async (token: string | undefined) => {
      if (token) {
        try {
          await this.grantService.storeUserToken(grant.targetSystem, token);
          this.snackBar.open(`Token stored for ${grant.targetSystem}`, 'OK', { duration: 3000 });
          this.loadGrants();
        } catch {
          this.snackBar.open(`Failed to store token for ${grant.targetSystem}`, 'OK', {
            duration: 5000,
          });
        }
      }
    });
  }

  async revoke(grant: UserGrant): Promise<void> {
    try {
      await this.grantService.revokeGrant(grant.targetSystem);
      this.snackBar.open(`Revoked ${grant.targetSystem}`, 'OK', { duration: 3000 });
      this.loadGrants();
    } catch {
      this.snackBar.open(`Failed to revoke ${grant.targetSystem}`, 'OK', { duration: 5000 });
    }
  }

  typeLabel(type: string): string {
    switch (type) {
      case 'OAUTH_AUTHORIZATION_CODE':
        return 'OAuth';
      case 'OAUTH_CLIENT_CREDENTIALS':
        return 'Client Credentials';
      case 'USER_PROVIDED_TOKEN':
        return 'User Token';
      case 'STATIC_API_KEY':
        return 'API Key';
      default:
        return type;
    }
  }

  statusIcon(status: string): string {
    switch (status) {
      case 'CONNECTED':
        return 'check_circle';
      case 'NOT_CONNECTED':
        return 'link_off';
      case 'EXPIRED':
        return 'schedule';
      default:
        return 'help';
    }
  }

  statusColor(status: string): string {
    switch (status) {
      case 'CONNECTED':
        return 'connected';
      case 'EXPIRED':
        return 'expired';
      default:
        return 'disconnected';
    }
  }
}
