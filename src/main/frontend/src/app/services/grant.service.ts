import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface UserGrant {
  targetSystem: string;
  type: 'OAUTH_AUTHORIZATION_CODE' | 'OAUTH_CLIENT_CREDENTIALS' | 'USER_PROVIDED_TOKEN' | 'STATIC_API_KEY';
  description: string | null;
  status: 'CONNECTED' | 'NOT_CONNECTED' | 'EXPIRED';
  grantedAt: string | null;
  expiresAt: string | null;
  hasRefreshToken: boolean;
  requireWorkloadIdentity: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class GrantService {
  private http = inject(HttpClient);

  async listGrants(): Promise<UserGrant[]> {
    return firstValueFrom(this.http.get<UserGrant[]>('/api/grants'));
  }

  async initiateOAuthGrant(targetSystem: string): Promise<string> {
    const result = await firstValueFrom(
      this.http.post<{ authorizationUrl: string }>(`/api/grants/${targetSystem}/authorize`, {})
    );
    return result.authorizationUrl;
  }

  async storeUserToken(targetSystem: string, token: string): Promise<void> {
    await firstValueFrom(this.http.post(`/api/grants/${targetSystem}/token`, { token }));
  }

  async revokeGrant(targetSystem: string): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/grants/${targetSystem}`));
  }
}
