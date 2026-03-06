import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

export interface TokenEntryDialogData {
  targetSystem: string;
  description: string | null;
}

@Component({
  selector: 'app-token-entry-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  templateUrl: './token-entry-dialog.component.html',
  styleUrl: './token-entry-dialog.component.scss',
})
export class TokenEntryDialogComponent {
  protected readonly dialogRef = inject(MatDialogRef<TokenEntryDialogComponent>);
  protected readonly data: TokenEntryDialogData = inject(MAT_DIALOG_DATA);
  protected token = '';
  protected showToken = false;

  submit(): void {
    if (this.token.trim()) {
      this.dialogRef.close(this.token.trim());
    }
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
