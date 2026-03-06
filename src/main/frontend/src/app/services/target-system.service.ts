import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface TargetSystem {
  name: string;
  type: string;
  description: string | null;
}

@Injectable({
  providedIn: 'root',
})
export class TargetSystemService {
  private http = inject(HttpClient);

  async listTargetSystems(): Promise<TargetSystem[]> {
    return firstValueFrom(this.http.get<TargetSystem[]>('/api/target-systems'));
  }
}
