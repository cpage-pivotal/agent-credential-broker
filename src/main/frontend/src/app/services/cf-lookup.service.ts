import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface ResolvedWorkload {
  orgName: string;
  orgGuid: string;
  spaceName: string;
  spaceGuid: string;
  appName: string;
  appGuid: string;
}

@Injectable({
  providedIn: 'root',
})
export class CfLookupService {
  private http = inject(HttpClient);

  async resolveWorkload(orgName: string, spaceName: string, appName: string): Promise<ResolvedWorkload> {
    const params = new HttpParams()
      .set('org', orgName)
      .set('space', spaceName)
      .set('app', appName);

    return firstValueFrom(
      this.http.get<ResolvedWorkload>('/api/cf/resolve-workload', { params })
    );
  }
}
