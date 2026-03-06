import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface DelegationResponse {
  id: string;
  agentId: string;
  allowedSystems: string[];
  createdAt: string;
  expiresAt: string;
  revoked: boolean;
  token: string | null;
}

export interface CreateDelegationRequest {
  agentId: string;
  allowedSystems: string[];
  ttlHours: number;
}

@Injectable({
  providedIn: 'root',
})
export class DelegationService {
  private http = inject(HttpClient);

  async listDelegations(): Promise<DelegationResponse[]> {
    return firstValueFrom(this.http.get<DelegationResponse[]>('/api/delegations'));
  }

  async createDelegation(request: CreateDelegationRequest): Promise<DelegationResponse> {
    return firstValueFrom(this.http.post<DelegationResponse>('/api/delegations', request));
  }

  async revokeDelegation(id: string): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/delegations/${id}`));
  }

  async refreshDelegation(id: string): Promise<DelegationResponse> {
    return firstValueFrom(this.http.post<DelegationResponse>(`/api/delegations/${id}/refresh`, {}));
  }
}
