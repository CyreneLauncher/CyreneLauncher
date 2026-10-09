
static uint32_t region[3 * 262144] __attribute__((aligned(256)));
static uint32_t fence[64] __attribute__((aligned(256)));
static struct orbis_chain_plan plan, validation;
static void tail(uint32_t *ib, unsigned n) {
   ib[n-4] = ORBIS_PM4_TYPE3(0x10, 3);
   ib[n-3] = 0xabc; ib[n-2] = 0xdef; ib[n-1] = 0x123;
}
static void jump(uint32_t *t, uint32_t *ib, unsigned n) {
   uint64_t a = (uintptr_t)ib;
   t[0] = ORBIS_PM4_TYPE3(0x3f, 3); t[1] = a;
   t[2] = a >> 32; t[3] = (1u << 20) | (1u << 23) | n;
}
int main(int argc, char **argv) {
   assert(argc == 2);
   orbis_va_base = (uintptr_t)region; orbis_va_end = orbis_va_base + sizeof(region);
   uint32_t *a=region, *b=region+262144, *c=region+524288;
   for (unsigned i=0; i<ARRAY_SIZE(region); i++) region[i]=0x80000000;
   tail(a,262144); tail(b,262144); tail(c,262144);
   void *dcb[3]={a,b,c}; uint32_t bytes[3]={262144*4,262144*4,262144*4};
   if (!strcmp(argv[1],"boot")) {
      orbis_selftest_chain();
      assert(orbis_va_base==(uintptr_t)region && orbis_va_end==orbis_va_base+sizeof(region));
   } else if (!strcmp(argv[1],"large")) {
      assert(orbis_prepare_chain(&plan,dcb,bytes,3));
      assert(plan.total_dw == 786432 && plan.count == 3);
      for (unsigned i=0;i<16;i++) fence[i]=0x80000000;
      orbis_link_chain(&plan,dcb,bytes,fence,16);
      memset(&validation,0,sizeof(validation));
      assert(orbis_walk_chain(&validation,a,262144,fence,16));
      assert(validation.count==4 && validation.nodes[3].ib==fence && validation.nodes[3].dw==16);
      assert(a[262140] == 0xc0023f00 && a[262141] == (uint32_t)(uintptr_t)b);
      assert((a[262143] & 0xffffff) == 0x940000);
      assert(c[262141] == (uint32_t)(uintptr_t)fence && (c[262143]&0xfffff)==16);
      orbis_restore_chain(&plan);
      assert(a[262140] == 0xc0021000 && a[262141] == 0xabc && a[262142] == 0xdef && a[262143] == 0x123);
      assert(c[262140] == 0xc0021000);
      /* Reuse gets a new fence target only after restoration. */
      assert(orbis_prepare_chain(&plan,dcb,bytes,3));
      orbis_link_chain(&plan,dcb,bytes,fence+32,16);
      assert(c[262141] == (uint32_t)(uintptr_t)(fence+32));
      orbis_restore_chain(&plan);
   } else if (!strcmp(argv[1],"padding")) {
      /* The over-count padding NOP must not hide the real chain. */
      a[0]=0xffff1000; jump(a+1,b,8); tail(b,8); bytes[0]=8*4;
      assert(orbis_prepare_chain(&plan,dcb,bytes,1));
      assert(plan.count==2 && plan.tails[0]==1 && plan.total_dw==16);
      orbis_link_chain(&plan,dcb,bytes,fence,16);
      assert(b[4]==0xc0023f00);
      orbis_restore_chain(&plan); assert(b[4]==0xc0021000);
   } else if (!strcmp(argv[1],"padding-long")) {
      a[0]=0xffff1000; jump(a+1,b,8); tail(b,8);
      assert(orbis_prepare_chain(&plan,dcb,bytes,1));
      assert(plan.count==2 && plan.tails[0]==1);
   } else if (!strcmp(argv[1],"truncated-nop")) {
      a[0]=0xc0201000; bytes[0]=32; tail(a,8);
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
   } else if (!strcmp(argv[1],"cycle")) {
      jump(a,a,8); bytes[0]=8*4;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
      assert(strstr(plan.why,"repeat"));
   } else if (!strcmp(argv[1],"bounds")) {
      jump(a,(uint32_t *)(uintptr_t)(orbis_va_end-4),8); bytes[0]=8*4;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
   } else if (!strcmp(argv[1],"slot")) {
      c[262140]=0x80000000;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,3));
      assert(a[262140]==0xc0021000); /* failure is atomic */
   } else if (!strcmp(argv[1],"short")) {
      a[0]=0xc0003f00; a[1]=0; bytes[0]=8;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
   } else if (!strcmp(argv[1],"zero")) {
      a[0]=0; bytes[0]=8*4; tail(a,8);
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
   } else if (!strcmp(argv[1],"overlap")) {
      dcb[1]=a; bytes[0]=bytes[1]=8*4; tail(a,8);
      assert(!orbis_prepare_chain(&plan,dcb,bytes,2));
   } else if (!strcmp(argv[1],"payload")) {
      /* NOP-looking last four dwords inside a larger packet are not a trailer. */
      a[0]=ORBIS_PM4_TYPE3(0x10,7); tail(a,8); bytes[0]=32;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
   } else if (!strcmp(argv[1],"depth")) {
      for(unsigned i=0;i<ORBIS_CHAIN_MAX_DEPTH;i++) jump(region+i*8,region+(i+1)*8,8);
      tail(region+ORBIS_CHAIN_MAX_DEPTH*8,8); bytes[0]=32;
      assert(!orbis_prepare_chain(&plan,dcb,bytes,1));
      assert(strstr(plan.why,"depth"));
   } else return 2;
   return 0;
}
