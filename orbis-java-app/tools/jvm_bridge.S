.text
.globl orbisCreateJavaVM
.type orbisCreateJavaVM,@function
.extern JNI_CreateJavaVM
orbisCreateJavaVM:
    jmp JNI_CreateJavaVM@PLT
.size orbisCreateJavaVM,.-orbisCreateJavaVM
